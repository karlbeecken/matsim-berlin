package org.matsim.run.policies;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.run.OpenBerlinScenario;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the OpenBerlin scenario with the U2 extended from S+U Pankow to U Ossietzkyplatz and the U9
 * extended from U Osloer Straße to S+U Pankow-Heinersdorf.
 */
public class OpenBerlinU2U9ExtensionScenario extends OpenBerlinScenario {

	private static final Logger log = LogManager.getLogger(OpenBerlinU2U9ExtensionScenario.class);

	// List of new stations with runtimes including stop times in seconds, from existing line end outwards
	private static final List<ExtensionStop> U2_EXTENSION = List.of(
		new ExtensionStop(
			"U Pankow Kirche",
			new Coord(798738.0, 5833686.0),
			90.0,
			Id.create("u2ext_pankowKirche_subway", TransitStopFacility.class),
			Id.create("163820", TransitStopArea.class),
			Id.createNodeId("pt_163820_subway")
		),
		new ExtensionStop(
			"U Ossietzkyplatz",
			new Coord(798323.0, 5834760.0),
			120.0,
			Id.create("u2ext_ossietzkyplatz_subway", TransitStopFacility.class),
			Id.create("439418", TransitStopArea.class),
			Id.createNodeId("pt_439418_subway")
		)
	);
	private static final List<ExtensionStop> U9_EXTENSION = List.of(
		new ExtensionStop(
			"U Stockholmer Straße",
			new Coord(797147.0, 5832492.0),
			90.0,
			Id.create("u9ext_stockholmerStrasse_subway", TransitStopFacility.class),
			Id.create("u9ext_stockholmerStrasse", TransitStopArea.class),
			Id.createNodeId("pt_u9ext_stockholmerStrasse_subway")
		),
		new ExtensionStop(
			"U Wollankstraße",
			new Coord(797727.0, 5832901.0),
			90.0,
			Id.create("u9ext_wollankstrasse_subway", TransitStopFacility.class),
			Id.create("199523", TransitStopArea.class),
			Id.createNodeId("pt_199523_subway")
		),
		new ExtensionStop(
			"U Rathaus Pankow",
			new Coord(798288.0, 5833457.0),
			120.0,
			Id.create("u9ext_rathausPankow_subway", TransitStopFacility.class),
			Id.create("372054", TransitStopArea.class),
			Id.createNodeId("pt_372054_subway")
		),
		new ExtensionStop(
			"U Pankow Kirche",
			new Coord(798815.0, 5833690.0),
			90.0,
			Id.create("u9ext_pankowKirche_subway", TransitStopFacility.class),
			Id.create("163820", TransitStopArea.class),
			Id.createNodeId("pt_163820_u9subway")
		),
		new ExtensionStop(
			"U Klaustaler Straße",
			new Coord(799601.0, 5833981.0),
			120.0,
			Id.create("u9ext_klaustalerStrasse_subway", TransitStopFacility.class),
			Id.create("74874", TransitStopArea.class),
			Id.createNodeId("pt_74874_subway")
		),
		new ExtensionStop(
			"U Pankow-Heinersdorf",
			new Coord(799991.0, 5834432.0),
			90.0,
			Id.create("u9ext_pankowHeinersdorf_subway", TransitStopFacility.class),
			Id.create("275805", TransitStopArea.class),
			Id.createNodeId("pt_275805_subway")
		)
	);

	private Network network;
	private TransitSchedule schedule;

	public static void main(String[] args) {
		MATSimApplication.execute(OpenBerlinU2U9ExtensionScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {
		config = super.prepareConfig(config);
		// append "u2-u9-extension" to the end of the run output folder, this avoids collision with the base case without writing a new config
		addRunOption(config, "u2-u9-extension");
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		network = scenario.getNetwork();
		schedule = scenario.getTransitSchedule();

		NetworkExtension u2Extension = buildNetworkExtension(U2_EXTENSION,
			Id.create("351543_subway", TransitStopFacility.class),
			// use Vinetastr. > Pankow link as template
			Id.createLinkId("pt_145219_subway-pt_351543_subway"));
		extendRoutes(findSubwayLine("U2"), u2Extension);

		NetworkExtension u9Extension = buildNetworkExtension(U9_EXTENSION,
			Id.create("613099_subway", TransitStopFacility.class),
			// use Nauener Platz > Osloer Str. link as template
			Id.createLinkId("pt_23069_subway-pt_613099_subway"));
		extendRoutes(findSubwayLine("U9"), u9Extension);

		validateSchedule();
	}

	/**
	 * Find a subway line by its short name in the schedule. Filtering for route type 402 (subway) is
	 * needed to not select replacement bus lines with the same name, e.g. the U2 replacement bus
	 * Alexanderplatz <> Senefelderplatz.
	 *
	 * @param shortName GTFS route short name, e.g. "U2"
	 * @return The matching transit line
	 */
	private TransitLine findSubwayLine(String shortName) {
		return schedule.getTransitLines().values().stream()
			.filter(l -> shortName.equals(l.getAttributes().getAttribute("gtfs_route_short_name"))
				&& "402".equals(l.getAttributes().getAttribute("gtfs_route_type")))
			.findFirst().orElseThrow();
	}

	/**
	 * Build the PT network extension beyond the given terminus.
	 *
	 * @param extensionStops New stations, from the current line end outwards
	 * @param terminusId     Stop facility of the current terminus the extension starts from
	 * @param linkTemplateId Existing PT link whose properties are copied onto the new connectors
	 * @return The created artifacts
	 */
	private NetworkExtension buildNetworkExtension(List<ExtensionStop> extensionStops, Id<TransitStopFacility> terminusId, Id<Link> linkTemplateId) {
		TransitStopFacility terminus = schedule.getFacilities().get(terminusId);
		// the terminus loop link serves as template for the new loop links
		Link loopTemplate = network.getLinks().get(terminus.getLinkId());
		Link linkTemplate = network.getLinks().get(linkTemplateId);

		List<TransitStopFacility> newStops = new ArrayList<>();
		List<Id<Link>> outboundLinks = new ArrayList<>();
		List<Id<Link>> inboundLinks = new ArrayList<>();

		// connect the first stop to the current last stop
		Node prevNode = loopTemplate.getToNode();

		for (ExtensionStop stop : extensionStops) {
			Node node = network.getFactory().createNode(stop.nodeId, stop.coord);
			network.addNode(node);

			// add new stop facility
			TransitStopFacility facility = schedule.getFactory().createTransitStopFacility(stop.id, stop.coord, false);
			facility.setStopAreaId(stop.stopArea);
			facility.setName(stop.name);
			facility.getAttributes().putAttribute("stopFilter", "station_S/U/RE/RB");
			schedule.addStopFacility(facility);
			newStops.add(facility);

			// add link to the new stop...
			Link linkTo = addConnector(linkTemplate, prevNode, node);
			outboundLinks.add(linkTo.getId());

			// ...and link back to the previous stop...
			Link linkFrom = addConnector(linkTemplate, node, prevNode);
			inboundLinks.addFirst(linkFrom.getId());

			// ...and finally add a loop linking the new stop to itself
			Link loopLink = network.getFactory().createLink(Id.createLinkId(node.getId()), node, node);
			copyLinkProperties(loopTemplate, loopLink);
			network.addLink(loopLink);
			outboundLinks.add(loopLink.getId());
			inboundLinks.addFirst(loopLink.getId());
			facility.setLinkId(loopLink.getId());

			prevNode = node;
		}

		return new NetworkExtension(extensionStops, terminus, newStops, outboundLinks, inboundLinks);
	}

	/**
	 * Extend the transit line routes from the existing terminus to the new end station.
	 * The departure times of the existing routes are unchanged, the new departures
	 * are appended/prepended with earlier/later departure times respectively.
	 *
	 * @param line      Transit line of which routes are extended
	 * @param extension Network extension to build upon
	 */
	private void extendRoutes(TransitLine line, NetworkExtension extension) {
		List<ExtensionStop> extensionStops = extension.definition;
		List<TransitStopFacility> newStops = extension.newStops;
		TransitStopFacility terminus = extension.terminus;
		double offset = extensionStops.stream().mapToDouble(s -> s.runTimeFromPrevious).sum();

		// copy the list because we will modify it while iterating
		for (TransitRoute oldRoute : List.copyOf(line.getRoutes().values())) {
			// check if the route begins or ends at the old terminus, do nothing if it doesn't (e.g. short-turning services)
			boolean endsAtTerminus = oldRoute.getStops().getLast().getStopFacility().getId().equals(terminus.getId());
			boolean startsAtTerminus = oldRoute.getStops().getFirst().getStopFacility().getId().equals(terminus.getId());
			if (!endsAtTerminus && !startsAtTerminus) {
				continue;
			}

			List<Id<Link>> links = new ArrayList<>();
			links.add(oldRoute.getRoute().getStartLinkId());
			links.addAll(oldRoute.getRoute().getLinkIds());
			links.add(oldRoute.getRoute().getEndLinkId());

			List<TransitRouteStop> stops = new ArrayList<>();
			if (endsAtTerminus) {
				// for routes towards the old terminus, we can simply copy all stops without any offset...
				oldRoute.getStops().forEach(s -> stops.add(copyStop(s, 0.0)));

				double accumulatedRunTime = oldRoute.getStops().getLast().getArrivalOffset().seconds();
				for (int i = 0; i < extensionStops.size(); i++) {
					ExtensionStop stop = extensionStops.get(i);

					// ...and add the new stops with the correct offset
					accumulatedRunTime += stop.runTimeFromPrevious;
					stops.add(newStop(newStops.get(i), accumulatedRunTime));

				}
				links.addAll(extension.outboundLinks);
			} else {
				// for routes departing at the old terminus, we need to add our new stops in the beginning...
				double accumulatedRunTime = 0.0;
				for (int i = extensionStops.size() - 1; i >= 0; i--) {
					ExtensionStop stop = extensionStops.get(i);

					stops.add(newStop(newStops.get(i), accumulatedRunTime));
					accumulatedRunTime += stop.runTimeFromPrevious;

				}
				// ...and then append all existing stops with an offset
				oldRoute.getStops().forEach(s -> stops.add(copyStop(s, offset)));
				links.addAll(0, extension.inboundLinks);
			}

			TransitRoute newRoute = schedule.getFactory().createTransitRoute(oldRoute.getId(), RouteUtils.createNetworkRoute(links), stops, oldRoute.getTransportMode());
			AttributesUtils.copyAttributesFromTo(oldRoute, newRoute);
			for (Departure dep : oldRoute.getDepartures().values()) {
				/* If the route starts at the old terminus, move its departure at the new start station earlier according
				 * to the total offset. This here is the initial departure, of which only one exists for each train run;
				 * all further stations get their departure automatically from the stop offset times defined in the route.
				 * Subtracting the accumulated offset of the newly added stations keeps the running times at all existing
				 * stations the same (transfers!). */
				Departure newDep = schedule.getFactory().createDeparture(dep.getId(), dep.getDepartureTime() - (startsAtTerminus ? offset : 0.0));
				newDep.setVehicleId(dep.getVehicleId());
				newRoute.addDeparture(newDep);
			}

			// replace the existing route from/to the old terminus with the one from/to the new end station
			line.removeRoute(oldRoute);
			line.addRoute(newRoute);
			log.info("Extended route {} {} {}", oldRoute.getId(), startsAtTerminus ? "from" : "to", extensionStops.getLast().name);
		}
	}

	/**
	 * Validate the transit schedule after the U2+U9 extension.
	 */
	private void validateSchedule() {
		TransitScheduleValidator.ValidationResult validationResult = TransitScheduleValidator.validateAll(schedule, network);
		if (!validationResult.isValid()) {
			for (TransitScheduleValidator.ValidationResult.ValidationIssue<?> issue : validationResult.getIssues()) {
				log.error(issue.getMessage());
			}
			throw new IllegalStateException("invalid transit schedule after U2/U9 extension");
		}
	}

	/**
	 * Create a connector between two PT nodes.
	 *
	 * @param template Link template to use for the connector
	 * @param from     Start node
	 * @param to       End node
	 * @return The created connector link
	 */
	private Link addConnector(Link template, Node from, Node to) {
		Link connector = network.getFactory().createLink(Id.createLinkId(from.getId() + "-" + to.getId()), from, to);
		copyLinkProperties(template, connector);
		connector.setLength(NetworkUtils.getEuclideanDistance(from.getCoord(), to.getCoord()));
		network.addLink(connector);
		return connector;
	}

	/**
	 * Copy link properties from one link to another.
	 *
	 * @param template Link to copy properties from
	 * @param target   Link to copy properties to
	 */
	private static void copyLinkProperties(Link template, Link target) {
		target.setLength(template.getLength());
		target.setFreespeed(template.getFreespeed());
		target.setCapacity(template.getCapacity());
		target.setNumberOfLanes(template.getNumberOfLanes());
		target.setAllowedModes(template.getAllowedModes());
	}

	/**
	 * Copy a stop with a time offset.
	 *
	 * @param s     Stop to copy
	 * @param shift Arrival/departure time offset in seconds
	 * @return Copied stop
	 */
	private TransitRouteStop copyStop(TransitRouteStop s, double shift) {
		TransitRouteStop copy = schedule.getFactory().createTransitRouteStop(s.getStopFacility(), s.getArrivalOffset().seconds() + shift, s.getDepartureOffset().seconds() + shift);
		copy.setAwaitDepartureTime(s.isAwaitDepartureTime());
		return copy;
	}

	/**
	 * Create a new stop with a time offset.
	 *
	 * @param facility The Stop Facility to link to
	 * @param offset   Arrival/departure time offset in seconds
	 * @return The created stop
	 */
	private TransitRouteStop newStop(TransitStopFacility facility, double offset) {
		TransitRouteStop stop = schedule.getFactory().createTransitRouteStop(facility, offset, offset);
		stop.setAwaitDepartureTime(true);
		return stop;
	}

	private record ExtensionStop(
		String name, Coord coord, double runTimeFromPrevious,
		Id<TransitStopFacility> id, Id<TransitStopArea> stopArea, Id<Node> nodeId) {
	}


	private record NetworkExtension(
		List<ExtensionStop> definition, TransitStopFacility terminus, List<TransitStopFacility> newStops,
		List<Id<Link>> outboundLinks, List<Id<Link>> inboundLinks) {
	}
}
