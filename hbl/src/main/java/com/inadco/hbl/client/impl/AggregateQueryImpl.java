/*
 * 
 *  Copyright © 2010, 2011 Inadco, Inc. All rights reserved.
 *  
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *  
 *  
 */
package com.inadco.hbl.client.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.Validate;

import com.inadco.hbl.api.Cube;
import com.inadco.hbl.api.Cuboid;
import com.inadco.hbl.api.Dimension;
import com.inadco.hbl.api.Hierarchy;
import com.inadco.hbl.api.Range;
import com.inadco.hbl.client.AggregateQuery;
import com.inadco.hbl.client.AggregateResultSet;
import com.inadco.hbl.client.impl.scanner.ScanSpec;

public class AggregateQueryImpl implements AggregateQuery {

    private Cube                    cube;
    /**
     * dim name -> range slice requested
     */
    private Map<String, Set<Slice>> dimSlices       = new HashMap<String, Set<Slice>>();
    private Set<String>             measures        = new HashSet<String>();

    private List<String>            groupDimensions = new ArrayList<String>();

    public AggregateQueryImpl(Cube cube) {
        super();
        this.cube = cube;
    }

    @Override
    public AggregateQuery addMeasure(String measure) {
        Validate.notNull(measure);
        measures.add(measure);
        return this;
    }

    @Override
    public AggregateQuery addGroupBy(String dimName) {
        Validate.notNull(dimName);
        Validate.isTrue(cube.getDimensions().containsKey(dimName), "no such dimension found");

        groupDimensions.add(dimName);
        return this;
    }

    @Override
    public AggregateQuery addClosedSlice(String dimension, Object leftBound, Object rightBound) {
        return addSlice(dimension, leftBound, false, rightBound, false);
    }

    @Override
    public AggregateQuery addOpenSlice(String dimension, Object leftBound, Object rightBound) {
        return addSlice(dimension, leftBound, true, rightBound, true);
    }

    @Override
    public AggregateQuery addHalfOpenSlice(String dimension, Object leftBound, Object rightBound) {
        return addSlice(dimension, leftBound, false, rightBound, true);
    }

    @Override
    public AggregateQuery addSlice(String dimension,
                                   Object leftBound,
                                   boolean leftOpen,
                                   Object rightBound,
                                   boolean rightOpen) {
        Validate.notNull(dimension);
        Validate.isTrue(cube.getDimensions().containsKey(dimension));

        if (leftBound == null && rightBound == null) {
            dimSlices.remove(dimension);
            return this;
        }
        Set<Slice> sliceSet = dimSlices.get(dimension);
        if (sliceSet == null)
            dimSlices.put(dimension, sliceSet = new TreeSet<Slice>());
        sliceSet.add(new Slice(leftBound, leftOpen, rightBound, rightOpen));

        return this;
    }

    @Override
    public AggregateResultSet execute() throws IOException {

        Cuboid cuboid = findCuboid();

        Validate.notNull(cuboid, "Unable find suitable cuboid for the slice query.");

        // FIXME, TODO: check slices for overlapping. otherwise, if slices
        // overlap, not only we'd be performing more scans than needed, but
        // they will also contain duplicate counts.

        // for now we just have to assume that slices will not overlap.

        // create cartesian product of ScanSpec's
        List<ScanSpec> scans = new ArrayList<ScanSpec>();

        List<Range> partialSpec = new ArrayList<Range>();

        int numGroupKeys=groupDimensions.size();
        
        generateScanSpecs(cuboid,scans,partialSpec,0,numGroupKeys,SliceOperation.ADD);
        
        return null;

    }

    /**
     * Generate cartesian product of all individual dimension scans and also
     * flip scan operation between ADD and COMPLEMENT types
     * 
     * @param cuboid
     * @param scanHolder
     * @param partialSpec
     * @param dimIndex
     * @param numGroupKeys
     * @param so
     */
    private void generateScanSpecs(Cuboid cuboid,
                                   List<ScanSpec> scanHolder,
                                   List<Range> partialSpec,
                                   int dimIndex,
                                   int numGroupKeys,
                                   SliceOperation so) {
        List<Dimension> dimensions = cuboid.getCuboidDimensions();
        if (dimIndex == dimensions.size()) {
            // add leaf
            scanHolder.add(new ScanSpec(partialSpec.toArray(new Range[dimIndex]), cuboid, numGroupKeys, so));
            return;
        }
        Dimension dim = dimensions.get(dimIndex);
        Set<Slice> slices = dimSlices.get(dim.getName());
        if (slices == null) {
            // generate 'total' slice

            if (dim instanceof Hierarchy) {
                // kinda hack: current hierachies are to support [ALL] hierarchy
                // key
                // which is all 0's .
                byte[] allKey = new byte[dim.getKeyLen()];
                Range allpoint = new Range(allKey, allKey, true, false, false);
                partialSpec.set(dimIndex, allpoint);

            } else {
                // total range for a dimension: perhaps subefficient!
                int keylen;
                byte[] startKey = new byte[keylen = dim.getKeyLen()];
                byte[] endKey = new byte[keylen];
                Arrays.fill(endKey, (byte) 0xFF);
                Range allrange = new Range(startKey, endKey, true, false, false);
                partialSpec.set(dimIndex, allrange);
            }

            generateScanSpecs(cuboid, scanHolder, partialSpec, dimIndex + 1, numGroupKeys, so);
        } else {
            if (slices.size() != 0)
                throw new UnsupportedOperationException(
                    "queries to multiple slices of the same dimension are not supported (yet)!");
            Slice slice = slices.iterator().next();
            Range[] ranges = dim.optimizeSliceScan(slice);

            Validate.notEmpty(ranges);

            for (Range r : ranges) {
                SliceOperation nextSo = so;

                // clarification:
                // we introduce slice operation (a complement operation
                // in a plan or additive operation) just for the dimension
                // to be able to advise us on most optimal combination
                // of such.
                // Note that a complement slice S as in A\S becomes a union
                // as in A\(S\S2) = (A\S) U S2 if S2 \subset S.
                // so due to similar argumentation accross different dimensions,
                // we just invert slice operations in each subsequent dimension
                // encountered (since each subsequent dimension always operates
                // on a subset of prior dimension's hyper slice).
                if (r.getSliceOperation() == SliceOperation.COMPLEMENT) {
                    if (nextSo == SliceOperation.ADD)
                        nextSo = SliceOperation.COMPLEMENT;
                    else
                        nextSo = SliceOperation.ADD;
                    partialSpec.set(dimIndex, r);
                    generateScanSpecs(cuboid, scanHolder, partialSpec, dimIndex + 1, numGroupKeys, nextSo);
                }
            }
        }
    }

    @Override
    public void reset() {
        dimSlices.clear();
        measures.clear();
        groupDimensions.clear();
    }

    private Cuboid findCuboid() {

        // we need to find cuboid with composite keys where
        // grouping dimensions are stacked on the left but
        // where all of the sliced dimensions are also present (in any
        // position).

        Set<String> dimensionSubset = new HashSet<String>();
        dimensionSubset.addAll(dimSlices.keySet());
        dimensionSubset.addAll(groupDimensions);

        Cuboid cuboid = null;

        // find a suitable cuboid per above with fewest extra dimensions.
        for (Cuboid c : cube.getCuboids()) {

            List<String> cPath = c.getCuboidPath();
            // filter out smaller cubes
            if (cPath.size() < dimensionSubset.size())
                continue;

            // filter out those that don't contain all the dimensions we need
            if (!cPath.containsAll(dimensionSubset))
                continue;

            // now check group dimensions that must be stacked on the left.
            int cnt = groupDimensions.size();
            for (String dimName : cPath) {
                if (!groupDimensions.contains(dimName))
                    break;
                cnt--;
            }
            if (cnt > 0)
                continue;

            // found qualifying cuboid. good.
            if (cuboid == null || cPath.size() < cuboid.getCuboidPath().size())
                cuboid = c;
        }

        return cuboid;

    }

}