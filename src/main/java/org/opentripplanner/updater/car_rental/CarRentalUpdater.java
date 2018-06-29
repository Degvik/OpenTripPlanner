package org.opentripplanner.updater.car_rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;
import org.opentripplanner.graph_builder.linking.SimpleStreetSplitter;
import org.opentripplanner.routing.car_rental.CarRentalRegion;
import org.opentripplanner.routing.car_rental.CarRentalStation;
import org.opentripplanner.routing.car_rental.CarRentalStationService;
import org.opentripplanner.routing.edgetype.RentACarOffEdge;
import org.opentripplanner.routing.edgetype.RentACarOnEdge;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.vertextype.CarRentalStationVertex;
import org.opentripplanner.updater.GraphUpdaterManager;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.opentripplanner.updater.JsonConfigurable;
import org.opentripplanner.updater.PollingGraphUpdater;
import org.opentripplanner.util.NonLocalizedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public class CarRentalUpdater extends PollingGraphUpdater {

    private static final Logger LOG = LoggerFactory.getLogger(CarRentalUpdater.class);

    private static final String DEFAULT_NETWORK_LIST = "default";

    private static DecimalFormat format = new DecimalFormat("##.000000");

    private Graph graph;

    private SimpleStreetSplitter linker;

    private String network = "default";

    // processedRegions is a set of car network names. It keeps track of regions that are already
    // applied to the graph, so we don't apply them again.
    private Set<String> processedRegions = new HashSet<>();

    private CarRentalStationService service;

    private CarRentalDataSource source;

    private GraphUpdaterManager updaterManager;

    Map<CarRentalStation, CarRentalStationVertex> verticesByStation = new HashMap<>();

    @Override
    protected void runPolling() throws Exception {
        LOG.debug("Updating car rental stations from " + source);
        if (!source.update()) {
            LOG.debug("No updates");
            return;
        }
        List<CarRentalStation> stations = source.getStations();
        List<CarRentalRegion> regions = source.getRegions();

        // Filter out the regions that are already applied.
        regions = regions.stream()
            .filter(c -> !processedRegions.contains(c.network))
            .collect(Collectors.toList());

        // Create graph writer runnable to apply these stations and regions to the graph
        updaterManager.execute(new CarRentalGraphWriterRunnable(stations, regions));

        if (!regions.isEmpty()) {
            regions.forEach(r -> processedRegions.add(r.network));
        }
    }

    @Override
    protected void configurePolling(Graph graph, JsonNode config) throws Exception {
        String sourceType = config.path("sourceType").asText();
        CarRentalDataSource source = null;
        if (sourceType != null) {
            if (sourceType.equals("car2go")) {
                source = new Car2GoCarRentalDataSource();
            }
        }

        if (source == null) {
            throw new IllegalArgumentException("Unknown car rental source type: " + sourceType);
        } else if (source instanceof JsonConfigurable) {
            ((JsonConfigurable) source).configure(graph, config);
        }

        // Configure updater
        LOG.info("Setting up car rental updater.");
        this.graph = graph;
        this.source = source;
        this.network = config.path("networks").asText(DEFAULT_NETWORK_LIST);
        LOG.info("Creating car rental updater running every {} seconds : {}", frequencySec, source);
    }

    @Override
    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
        this.updaterManager = updaterManager;
    }

    @Override
    public void setup() throws Exception {
        // Creation of network linker library will not modify the graph
        linker = new SimpleStreetSplitter(graph);

        // Adding a car rental station service needs a graph writer runnable
        updaterManager.executeBlocking(new GraphWriterRunnable() {
            @Override
            public void run(Graph graph) {
                service = graph.getService(CarRentalStationService.class, true);
            }
        });
    }

    @Override
    public void teardown() {

    }

    private class CarRentalGraphWriterRunnable implements GraphWriterRunnable {

        private List<CarRentalStation> stations;
        private List<CarRentalRegion> regions;
        private GeometryFactory geometryFactory = new GeometryFactory();


        public CarRentalGraphWriterRunnable(List<CarRentalStation> stations, List<CarRentalRegion> regions) {
            this.stations = stations;
            this.regions = regions;
        }

        @Override
        public void run(Graph graph) {
            if (!this.stations.isEmpty()) {
                applyStations(graph);
            }
            if (!this.regions.isEmpty()) {
                applyRegions(graph);
            }
        }

        private void applyStations(Graph graph) {
            // Apply stations to graph
            Set<CarRentalStation> stationSet = new HashSet<>();
            Set<String> defaultNetworks = new HashSet<>(Arrays.asList(network));
            LOG.info("Updating {} rental car stations.", stations.size());
            /* add any new stations and update car counts for existing stations */
            for (CarRentalStation station : stations) {
                if (station.networks == null) {
                    /* API did not provide a network list, use default */
                    station.networks = defaultNetworks;
                }
                service.addCarRentalStation(station);
                stationSet.add(station);
                CarRentalStationVertex vertex = verticesByStation.get(station);
                if (vertex == null) {
                    vertex = new CarRentalStationVertex(graph, station);
                    if (!linker.link(vertex)) {
                        // the toString includes the text "Car rental station"
                        LOG.warn("{} not near any streets; it will not be usable.", station);
                    }
                    verticesByStation.put(station, vertex);
                    if (station.allowPickup)
                        new RentACarOnEdge(vertex, vertex, station.networks);
                    if (station.allowDropoff)
                        new RentACarOffEdge(vertex, vertex, station.networks);
                } else {
                    vertex.setCarsAvailable(station.carsAvailable);
                    vertex.setSpacesAvailable(station.spacesAvailable);
                }
            }
            // Remove existing stations that were not present in the update
            List<CarRentalStation> toRemove = new ArrayList<>();
            for (Entry<CarRentalStation, CarRentalStationVertex> entry : verticesByStation.entrySet()) {
                CarRentalStation station = entry.getKey();
                if (stationSet.contains(station))
                    continue;
                CarRentalStationVertex vertex = entry.getValue();
                if (graph.containsVertex(vertex)) {
                    graph.removeVertexAndEdges(vertex);
                }
                toRemove.add(station);
                service.removeCarRentalStation(station);
                // TODO: need to unsplit any streets that were split
            }
            for (CarRentalStation station : toRemove) {
                // post-iteration removal to avoid concurrent modification
                verticesByStation.remove(station);
            }
        }
        public void applyRegions(Graph graph) {
            // Adding car service regions to all edges of the network.
            LOG.info("Applying {} rental car regions.", regions.size());
            Collection<StreetEdge> edges = graph.getStreetEdges();
            Map<Coordinate, Set<String>> coordToNetworksMap = new HashMap<>();
            for (CarRentalRegion region : regions) {
                LOG.info("\t{}", region.network);
                service.addCarRentalRegion(region);
                Set<Coordinate> coordinates = intersectWithGraph(edges, region);

                coordinates.forEach(c -> coordToNetworksMap.putIfAbsent(c, new HashSet<>()));
                coordinates.forEach(c -> coordToNetworksMap.get(c).add(region.network));

            }
            addDropOffsToGraph(coordToNetworksMap);
            LOG.info("Added in total {} border dropoffs.", coordToNetworksMap.size());
            LOG.info("Finished applying service regions.");
        }

        /**
         * Labels edges that are inside the region with the car network name. For edges that are partially
         * inside the region, computes the intersection points and returns them.
         * Skips edges that are outside of the region.
         * @param edges
         * @param region The intersection locations
         */
        private Set<Coordinate> intersectWithGraph(Collection<StreetEdge> edges, CarRentalRegion region) {
            Set<Coordinate> coordinates = new HashSet<>();
            for (StreetEdge edge : edges) {
                Point[] edgePoints = getEdgeCoord(edge);
                boolean coversFrom = region.geometry.covers(edgePoints[0]);
                boolean coversTo = region.geometry.covers(edgePoints[1]);
                if (coversFrom && coversTo) {
                    edge.addCarNetwork(region.network);
                } else if (coversFrom || coversTo) {
                    coordinates.addAll(intersect(edgePoints, region));
                }
            }
            return coordinates;
        }

        /**
         * Finds the intersection points of the edge and the region.
         *
         * @param edgePoints an array of two points representing an edge
         * @param region the car service area
         * @return intersection coordinates
         */
        private Set<Coordinate> intersect(Point[] edgePoints, CarRentalRegion region) {
            // Intersect the edge with the region's geometry.
            Point from = edgePoints[0];
            Point to = edgePoints[1];
            LineString lineString = geometryFactory.createLineString(new Coordinate[]{from.getCoordinate(), to.getCoordinate()});
            Geometry intersectionLineStrings = region.geometry.intersection(lineString);
            // intersection results in one or more linestrings representing the parts of the edge that overlaps
            // the polygon.
            Set<Coordinate> intersectionPoints = new HashSet<>();
            for (int i = 0; i < intersectionLineStrings.getNumGeometries(); i++) {
                Geometry intersectionLineString = intersectionLineStrings.getGeometryN(i);
                for (Coordinate coordinate : intersectionLineString.getCoordinates()) {
                    if (coordinate.equals(equals(from.getCoordinate())) ||
                        coordinate.equals(equals(to.getCoordinate()))){
                        // Skip the 'from' and 'to' nodes of the edge and only return the intersection points.
                        // The reason is that we will create car drop off stations for those intersection points,
                        // and not for 'from' and 'to' nodes.
                        continue;
                    }
                    intersectionPoints.add(roundLatLng(coordinate));
                }
            }
            return intersectionPoints;
        }

        private Coordinate roundLatLng(Coordinate coordinate) {
            return new Coordinate(Double.parseDouble(format.format(coordinate.x)), Double.parseDouble(format.format(coordinate.y)), 0);
        }

        /**
         * Adds a car dropoff station at each given (location, networks) pairs.
         */
        private void addDropOffsToGraph(Map<Coordinate, Set<String>> coordToNetworksMap) {
            coordToNetworksMap.forEach((coord, networks) -> {
                CarRentalStation station = makeDropOffStation(coord, networks);
                CarRentalStationVertex vertex = new CarRentalStationVertex(graph, station);
                if (!linker.link(vertex)) {
                    LOG.warn("Ignoring {} since it's not near any streets; it will not be usable.", station);
                    return;
                }
                service.addCarRentalStation(station);
                new RentACarOffEdge(vertex, vertex, networks);
            });
        }

        /**
         * @param edge an Edge
         * @return an array of size 2 of Point, with the first and the second items representing the edge's
         *         `from` and `to` locations respectively.
         */
        private Point[] getEdgeCoord(final Edge edge) {
            Point fromPoint = geometryFactory.createPoint(new Coordinate(edge.getFromVertex().getX(), edge.getFromVertex().getY(), 0));
            Point toPoint = geometryFactory.createPoint(new Coordinate(edge.getToVertex().getX(), edge.getToVertex().getY(), 0));
            return new Point[]{fromPoint, toPoint};
        }

        private CarRentalStation makeDropOffStation(Coordinate coord, Set<String> networks) {
            CarRentalStation station = new CarRentalStation();
            String id = String.format("border_dropoff_%3.6f_%3.6f", coord.x, coord.y);

            station.allowDropoff = true;
            station.allowPickup = false;
            station.carsAvailable = 0;
            station.id = id;
            station.isBorderDropoff = true;
            station.name = new NonLocalizedString(id);
            station.networks = networks;
            station.spacesAvailable = Integer.MAX_VALUE;
            station.x = coord.x;
            station.y = coord.y;
            return station;
        }
    }
}