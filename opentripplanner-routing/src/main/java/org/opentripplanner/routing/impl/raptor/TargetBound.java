/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.impl.raptor;

import java.util.ArrayList;
import java.util.List;

import org.opentripplanner.common.geometry.DistanceLibrary;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.routing.algorithm.strategies.SearchTerminationStrategy;
import org.opentripplanner.routing.algorithm.strategies.SkipTraverseResultStrategy;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.ShortestPathTree;

import com.vividsolutions.jts.geom.Coordinate;

public class TargetBound implements SearchTerminationStrategy, SkipTraverseResultStrategy {

    List<State> bounders;

    private Vertex realTarget;

    private DistanceLibrary distanceLibrary = SphericalDistanceLibrary.getInstance();

    private Coordinate realTargetCoordinate;

    private double distanceToNearestTransitStop;

    private List<RaptorState> boundingStates;

    public TargetBound(Vertex realTarget, List<RaptorState> boundingStates, List<State> bounders) {
        this.realTarget = realTarget;
        this.realTargetCoordinate = realTarget.getCoordinate();
        this.distanceToNearestTransitStop = realTarget.getDistanceToNearestTransitStop();
        this.boundingStates = boundingStates;
        if (bounders == null) {
            this.bounders = new ArrayList<State>();
        } else {
            this.bounders = bounders;
        }
    }

    @Override
    public boolean shouldSearchContinue(Vertex origin, Vertex target, State current,
            ShortestPathTree spt, RoutingRequest traverseOptions) {
        if (current.getVertex() == realTarget) {
            bounders.add(current);
        }
        return true;
    }

    @Override
    public boolean shouldSkipTraversalResult(Vertex origin, Vertex target, State parent,
            State current, ShortestPathTree spt, RoutingRequest traverseOptions) {
        final Vertex vertex = current.getVertex();
        final double targetDistance = distanceLibrary.fastDistance(realTargetCoordinate,
                vertex.getCoordinate());

        final double remainingWalk = traverseOptions.maxWalkDistance
                - current.getWalkDistance();
        final double minWalk;
        double minTime = 0;
        if (targetDistance > remainingWalk) {
            // then we must have some transit + some walk.
            minWalk = this.distanceToNearestTransitStop + vertex.getDistanceToNearestTransitStop();
            minTime = traverseOptions.getBoardSlack();
        } else {
            // could walk directly to destination
            minWalk = targetDistance;
        }
        if (minWalk > remainingWalk)
            return true;

        final double optimisticDistance = current.getWalkDistance() + minWalk;
        minTime += (targetDistance - minWalk) / Raptor.MAX_TRANSIT_SPEED + minWalk
                / current.getOptions().getSpeedUpperBound();

        double stateTime = current.getTime() + minTime - traverseOptions.dateTime;
        
        // this makes speed worse for some reason.  Oh, probably because any
        // searches cut off here don't dominate other searches?

/*
        for (RaptorState bounder : boundingStates) {
            if (optimisticDistance > bounder.walkDistance && current.getTime() + minTime > bounder.arrivalTime)
                return true;
                
                double bounderWeight = bounder.walkDistance + bounder.arrivalTime - traverseOptions.dateTime;
                if (bounderWeight * 4 < stateWeight) {
                    return true;
                }
        }*/

        for (State bounder : bounders) {

            if (optimisticDistance > bounder.getWalkDistance() && current.getTime() + minTime > bounder.getTime()) 
                return true; // this path won't win on either time or distance

            //check that the new path is not much longer in time than the bounding path
            double bounderTime = bounder.getTime() - traverseOptions.dateTime;

            if (bounderTime * 1.5 < stateTime) {
                return true;
            }
        }
        return false;
    }

}
