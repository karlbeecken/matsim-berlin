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
 * Runs the OpenBerlin scenario with the U2 extended from S+U Pankow to a new stop U Ossietzkyplatz.
 */
public class OpenBerlinU2ExtensionScenario extends OpenBerlinScenario {

	private static final Logger log = LogManager.getLogger(OpenBerlinU2ExtensionScenario.class);

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

	private Network network;
	private TransitSchedule schedule;
	private final List<TransitStopFacility> newStops = new ArrayList<>();
	private final List<Id<Link>> outboundLinks = new ArrayList<>();
	private final List<Id<Link>> inboundLinks = new ArrayList<>();

	public static void main(String[] args) {
		MATSimApplication.execute(OpenBerlinU2ExtensionScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {
		config = super.prepareConfig(config);
		// append "u2-extension" to the end of the run output folder, this avoids collision with the base case without writing a new config
		addRunOption(config, "u2-extension");
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		network = scenario.getNetwork();
		schedule = scenario.getTransitSchedule();

		TransitLine u2 = findU2Line();
		buildNetworkExtension();
		extendRoutes(u2);
		validateSchedule();
	}

	/**
	 * Find the U2 transitline in the schedule.
	 *
	 * @return The U2 Transit Line
	 */
	private TransitLine findU2Line() {
		// be careful not to select the U2 replacement bus Alexanderplatz <> Senefelderplatz, route type 402 is subway
		return schedule.getTransitLines().values().stream().filter(l -> "U2".equals(l.getAttributes().getAttribute("gtfs_route_short_name")) && "402".equals(l.getAttributes().getAttribute("gtfs_route_type"))).findFirst().orElseThrow();
	}

	/**
	 * Build the PT network extension from S+U Pankow to the new end station.
	 */
	private void buildNetworkExtension() {
		// save U2 terminus loop link and existing link as template
		TransitStopFacility pankow = schedule.getFacilities().get(Id.create("351543_subway", TransitStopFacility.class));
		Link loopTemplate = network.getLinks().get(pankow.getLinkId());
		Node pankowNode = loopTemplate.getToNode();
		// use Vinetastr. > Pankow link as template
		Link linkTemplate = network.getLinks().get(Id.createLinkId("pt_145219_subway-pt_351543_subway"));


		// connect the first stop to the current last stop
		Node prevNode = pankowNode;

		for (ExtensionStop stop : U2_EXTENSION) {
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
	}

	/**
	 * Extend the U2 transit line routes from S+U Pankow to the new end station.
	 * The departure times of the existing routes are unchanged, the new departures
	 * are appended/prepended with earlier/later departure times respectively.
	 *
	 * @param u2 The U2 Transit Line
	 */
	private void extendRoutes(TransitLine u2) {
		double offset = U2_EXTENSION.stream().mapToDouble(s -> s.runTimeFromPrevious).sum();
		TransitStopFacility pankow = schedule.getFacilities().get(Id.create("351543_subway", TransitStopFacility.class));

		// copy the list because we will modify it while iterating
		for (TransitRoute oldRoute : List.copyOf(u2.getRoutes().values())) {
			// check if the route begins ir ends at Pankow, do nothing if it doesn't (e.g. short night lines)
			boolean endsAtPankow = oldRoute.getStops().getLast().getStopFacility().getId().equals(pankow.getId());
			boolean startsAtPankow = oldRoute.getStops().getFirst().getStopFacility().getId().equals(pankow.getId());
			if (!endsAtPankow && !startsAtPankow) {
				continue;
			}

			List<Id<Link>> links = new ArrayList<>();
			links.add(oldRoute.getRoute().getStartLinkId());
			links.addAll(oldRoute.getRoute().getLinkIds());
			links.add(oldRoute.getRoute().getEndLinkId());

			List<TransitRouteStop> stops = new ArrayList<>();
			if (endsAtPankow) {
				// for routes towards Pankow, we can simply copy all stops without any offset...
				oldRoute.getStops().forEach(s -> stops.add(copyStop(s, 0.0)));

				double accumulatedRunTime = oldRoute.getStops().getLast().getArrivalOffset().seconds();
				for (int i = 0; i < U2_EXTENSION.size(); i++) {
					ExtensionStop stop = U2_EXTENSION.get(i);

					// ...and add the new stops with the correct offset
					accumulatedRunTime += stop.runTimeFromPrevious;
					stops.add(newStop(newStops.get(i), accumulatedRunTime));

				}
				links.addAll(outboundLinks);
			} else {
				// for routes departing at Pankow, we need to add our new stop in the beginning...
				double accumulatedRunTime = 0.0;
				for (int i = U2_EXTENSION.size() - 1; i >= 0; i--) {
					ExtensionStop stop = U2_EXTENSION.get(i);

					stops.add(newStop(newStops.get(i), accumulatedRunTime));
					accumulatedRunTime += stop.runTimeFromPrevious;

				}
				// ...and then append all existing stops with an offset
				oldRoute.getStops().forEach(s -> stops.add(copyStop(s, offset)));
				links.addAll(0, inboundLinks);
			}

			TransitRoute newRoute = schedule.getFactory().createTransitRoute(oldRoute.getId(), RouteUtils.createNetworkRoute(links), stops, oldRoute.getTransportMode());
			AttributesUtils.copyAttributesFromTo(oldRoute, newRoute);
			for (Departure dep : oldRoute.getDepartures().values()) {
				/* If the route starts at Pankow, move departure at the new start station earlier according to the total offset.
				 * This here is the initial departure, of which only one exists for each train run; all further stations get
				 * their departure automatically from the stop offset times defined in the route. Subtracting the accumulated
				 * offset of the newly added stations keeps the running times at all existing stations the same (transfers!). */
				Departure newDep = schedule.getFactory().createDeparture(dep.getId(), dep.getDepartureTime() - (startsAtPankow ? offset : 0.0));
				newDep.setVehicleId(dep.getVehicleId());
				newRoute.addDeparture(newDep);
			}

			// replace the existing route from/to Pankow with the one from/to the new end station
			u2.removeRoute(oldRoute);
			u2.addRoute(newRoute);
			log.info("Extended route {} {} {}", oldRoute.getId(), startsAtPankow ? "from" : "to", U2_EXTENSION.getLast().name);
		}
	}

	/**
	 * Validate the transit schedule after the U2 extension.
	 */
	private void validateSchedule() {
		TransitScheduleValidator.ValidationResult validationResult = TransitScheduleValidator.validateAll(schedule, network);
		if (!validationResult.isValid()) {
			for (TransitScheduleValidator.ValidationResult.ValidationIssue issue : validationResult.getIssues()) {
				log.error(issue.getMessage());
			}
			throw new IllegalStateException("invalid transit schedule after U2 extension");
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

	private record ExtensionStop(String name, Coord coord, double runTimeFromPrevious, Id<TransitStopFacility> id, Id<TransitStopArea> stopArea, Id<Node> nodeId) { }
}
