package org.opentripplanner.routing.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.onebusaway.gtfs.impl.calendar.CalendarServiceImpl;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.calendar.CalendarServiceData;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.opentripplanner.common.model.NamedPlace;
import org.opentripplanner.routing.algorithm.strategies.GenericAStarFactory;
import org.opentripplanner.routing.algorithm.strategies.RemainingWeightHeuristic;
import org.opentripplanner.routing.error.TransitTimesException;
import org.opentripplanner.routing.error.VertexNotFoundException;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.impl.DefaultRemainingWeightHeuristicFactoryImpl;
import org.opentripplanner.routing.location.StreetLocation;
import org.opentripplanner.routing.services.RemainingWeightHeuristicFactory;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A RoutingContext holds information needed to carry out a search for a particular TraverseOptions,
 * on a specific graph. Includes things like (temporary) endpoint vertices, transfer tables, 
 * service day caches, etc.
 * 
 * @author abyrd
 */
public class RoutingContext implements Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingContext.class);
    
    private static RemainingWeightHeuristicFactory heuristicFactory = new DefaultRemainingWeightHeuristicFactoryImpl();
    
    /* FINAL FIELDS */
    
    public TraverseOptions opt; // not final so we can reverse-optimize
    public final Graph graph;
    public final Vertex fromVertex;
    public final Vertex toVertex;
    // origin means "where the initial state will be located" not "the beginning of the trip from the user's perspective"
    public final Vertex origin;
    // target means "where this search will terminate" not "the end of the trip from the user's perspective"
    public final Vertex target;
    ArrayList<Vertex> intermediateVertices = new ArrayList<Vertex>();
    public final boolean goalDirection = true;
    //public final Calendar calendar;
    public final CalendarServiceImpl calendarService;
    public final Map<AgencyAndId, Set<ServiceDate>> serviceDatesByServiceId = new HashMap<AgencyAndId, Set<ServiceDate>>();
    public final GenericAStarFactory aStarSearchFactory = null;
    public final RemainingWeightHeuristic remainingWeightHeuristic;
    public final TransferTable transferTable;
            
    /**
     * Cache lists of which transit services run on which midnight-to-midnight periods. This ties a
     * TraverseOptions to a particular start time for the duration of a search so the same options
     * cannot be used for multiple searches concurrently. To do so this cache would need to be moved
     * into StateData, with all that entails.
     */
    public ArrayList<ServiceDay> serviceDays;

    /**
     * The search will be aborted if it is still running after this time (in milliseconds since the 
     * epoch). A negative or zero value implies no limit. 
     * This provides an absolute timeout, whereas the maxComputationTime is relative to the 
     * beginning of an individual search. While the two might seem equivalent, we trigger search 
     * retries in various places where it is difficult to update relative timeout value. 
     * The earlier of the two timeouts is applied. 
     */
    public long searchAbortTime = 0;
    
    
    /* CONSTRUCTORS */
    
    public RoutingContext(TraverseOptions traverseOptions, Graph graph) {
        this(traverseOptions, graph, true);
    }

    public RoutingContext(TraverseOptions traverseOptions, Graph graph, boolean useServiceDays) {
        opt = traverseOptions;
        this.graph = graph;
        fromVertex = graph.streetIndex.getVertexForPlace(opt.getFromPlace(), opt);
        toVertex = graph.streetIndex.getVertexForPlace(opt.getToPlace(), opt, fromVertex);
        // state.reversedClone() will have to set the vertex, not get it from opts
        origin = opt.arriveBy ? toVertex : fromVertex;
        target = opt.arriveBy ? fromVertex : toVertex;
        checkEndpointVertices();
        findIntermediateVertices();
        CalendarServiceData csData = graph.getService(CalendarServiceData.class);
        if (csData != null) {
            calendarService = new CalendarServiceImpl();
            calendarService.setData(csData);
        } else {
            calendarService = null;
        }
        transferTable = graph.getTransferTable();
        if (useServiceDays)
            setServiceDays();
        remainingWeightHeuristic = heuristicFactory.getInstanceForSearch(opt);
        if (opt.getModes().isTransit()
            && ! graph.transitFeedCovers(opt.dateTime)) {
            // user wants a path through the transit network,
            // but the date provided is outside those covered by the transit feed.
            throw new TransitTimesException();
        }
    }
    
    
    /* INSTANCE METHODS */
    
    private void checkEndpointVertices() {
        ArrayList<String> notFound = new ArrayList<String>();
        if (fromVertex == null)
            notFound.add("from");
        if (toVertex == null)
            notFound.add("to");
        if (notFound.size() > 0)
            throw new VertexNotFoundException(notFound);
    }
    
    private void findIntermediateVertices() {
        if (opt.intermediatePlaces == null)
            return;
        ArrayList<String> notFound = new ArrayList<String>();
        int i = 0;
        for (NamedPlace intermediate : opt.intermediatePlaces) {
            Vertex vertex = graph.streetIndex.getVertexForPlace(intermediate, opt);
            if (vertex == null) {
                notFound.add("intermediate." + i);
            } else {
                intermediateVertices.add(vertex);
            }
            i += 1;
        }
        if (notFound.size() > 0) {
            throw new VertexNotFoundException(notFound);
        }        
    }

    /**
     *  Cache ServiceDay objects representing which services are running yesterday, today, and tomorrow relative
     *  to the search time. This information is very heavily used (at every transit boarding) and Date operations were
     *  identified as a performance bottleneck. Must be called after the TraverseOptions already has a CalendarService set. 
     */
    public void setServiceDays() {
        final long SEC_IN_DAY = 60 * 60 * 24;
        final long time = opt.getSecondsSinceEpoch();
        this.serviceDays = new ArrayList<ServiceDay>(3);
        if (calendarService == null) {
            LOG.warn("TraverseOptions has no CalendarService or GTFSContext. Transit will never be boarded.");
            return;
        }
        // This should be a valid way to find yesterday and tomorrow,
        // since DST changes more than one hour after midnight in US/EU.
        // But is this true everywhere?
        for (String agency : graph.getAgencyIds()) {
            addIfNotExists(this.serviceDays, new ServiceDay(time - SEC_IN_DAY, calendarService, agency));
            addIfNotExists(this.serviceDays, new ServiceDay(time, calendarService, agency));
            addIfNotExists(this.serviceDays, new ServiceDay(time + SEC_IN_DAY, calendarService, agency));
        }
    }

    private static<T> void addIfNotExists(ArrayList<T> list, T item) {
        if (!list.contains(item)) {
            list.add(item);
        }
    }

    /** check if the start and end locations are accessible */
    public boolean isAccessible() {
        if (opt.getWheelchair()) {
            return isWheelchairAccessible(fromVertex) &&
                   isWheelchairAccessible(toVertex);
        }
        return true;
    }

    // this could be handled by method overloading on Vertex
    public boolean isWheelchairAccessible(Vertex v) {
        if (v instanceof TransitStop) {
            TransitStop ts = (TransitStop) v;
            return ts.hasWheelchairEntrance();
        } else if (v instanceof StreetLocation) {
            StreetLocation sl = (StreetLocation) v;
            return sl.isWheelchairAccessible();
        }
        return true;
    }

    public boolean serviceOn(AgencyAndId serviceId, ServiceDate serviceDate) {
        Set<ServiceDate> dates = serviceDatesByServiceId.get(serviceId);
        if (dates == null) {
            dates = calendarService.getServiceDatesForServiceId(serviceId);
            serviceDatesByServiceId.put(serviceId, dates);
        }
        return dates.contains(serviceDate);
    }
    
    /** 
     * When a routing context is garbage collected, there should be no more references
     * to the temporary vertices it created. We need to detach its edges from the permanent graph.
     */
    @Override public void finalize() {
        int nRemoved = this.destroy();
        if (nRemoved > 0) {
            LOG.warn("Temporary edges were removed during garbage collection. " +
                     "This is probably because a routing context was not properly destroyed.");
        }
    }
    
    /** 
     * Tear down this routing context, removing any temporary edges. 
     * @returns the number of edges removed. 
     */
    public int destroy() {
        int nRemoved = 0;
        nRemoved += origin.removeTemporaryEdges();
        nRemoved += target.removeTemporaryEdges();
        for (Vertex v : intermediateVertices)
            nRemoved += v.removeTemporaryEdges();
        return nRemoved;
    }

}