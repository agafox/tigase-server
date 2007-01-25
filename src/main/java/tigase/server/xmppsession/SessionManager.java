/*
 *  Package Tigase XMPP/Jabber Server
 *  Copyright (C) 2004, 2005, 2006
 *  "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software Foundation,
 *  Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.server.xmppsession;

//import tigase.auth.TigaseConfiguration;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import tigase.auth.LoginHandler;
import tigase.auth.TigaseSaslProvider;
import tigase.conf.Configurable;
import tigase.db.DataOverwriteException;
import tigase.db.NonAuthUserRepository;
import tigase.db.RepositoryFactory;
import tigase.db.TigaseDBException;
import tigase.db.UserAuthRepository;
import tigase.db.UserNotFoundException;
import tigase.db.UserRepository;
import tigase.server.AbstractMessageReceiver;
import tigase.server.MessageReceiver;
import tigase.server.Packet;
import tigase.server.Command;
import tigase.server.XMPPService;
import tigase.server.Permissions;
import tigase.stats.StatRecord;
import tigase.util.ElementUtils;
import tigase.util.JID;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.ProcessorFactory;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPPostprocessorIfc;
import tigase.xmpp.XMPPPreprocessorIfc;
import tigase.xmpp.XMPPProcessorIfc;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.XMPPSession;
import tigase.xmpp.XMPPStopListenerIfc;

import static tigase.server.xmppsession.SessionManagerConfig.*;

/**
 * Class SessionManager
 *
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class SessionManager extends AbstractMessageReceiver
	implements Configurable, XMPPService, LoginHandler {

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger("tigase.server.xmppsession.SessionManager");

	private UserRepository user_repository = null;
	private UserAuthRepository auth_repository = null;
	private NonAuthUserRepository naUserRepository = null;

	private Element[] DISCO_FEATURES = {};
	private String[] admins = {"admin@localhost"};

	private Map<String, XMPPSession> sessionsByNodeId =
		new ConcurrentSkipListMap<String, XMPPSession>();
	private Map<String, XMPPResourceConnection> connectionsByFrom =
		new ConcurrentSkipListMap<String, XMPPResourceConnection>();

	private Map<String, XMPPPreprocessorIfc> preProcessors =
		new ConcurrentSkipListMap<String, XMPPPreprocessorIfc>();
	private Map<String, XMPPProcessorIfc> processors =
		new ConcurrentSkipListMap<String, XMPPProcessorIfc>();
	private Map<String, XMPPPostprocessorIfc> postProcessors =
		new ConcurrentSkipListMap<String, XMPPPostprocessorIfc>();
	private Map<String, XMPPStopListenerIfc> stopListeners =
		new ConcurrentSkipListMap<String, XMPPStopListenerIfc>();

	private long closedConnections = 0;

	public void processPacket(Packet packet) {
		log.finest("Processing packet: " + packet.getStringData());
		if (packet.isCommand()) {
			processCommand(packet);
			packet.processedBy("SessionManager");
			// No more processing is needed for command packet
			// 			return;
		} // end of if (pc.isCommand())
		XMPPResourceConnection conn = getXMPPResourceConnection(packet);
		if (conn == null) {
			// It might be a message _to_ some user on this server
			// so let's look for established session for this user...
			final String to = packet.getElemTo();
			if (to != null) {
				conn = getResourceConnection(to);
				if (conn == null) {
					// It might be message to admin
					if (processAdmins(packet)) {
						// No more processing is needed....
						return;
					}
				}
			} else {
				// Hm, not sure what should I do now....
				// Maybe I should treat it as message to admin....
				log.info("Message without TO attribute set, don't know what to do wih this: "
					+ packet.getStringData());
			} // end of else
		} // end of if (conn == null)

		// Preprocess..., all preprocessors get all messages to look at.
		// I am not sure if this is correct for now, let's try to do it this
		// way and maybe change it later.
		// If any of them returns true - it means processing should stop now.
		// That is needed for preprocessors like privacy lists which should
		// block certain packets.

		Queue<Packet> results = new LinkedList<Packet>();

		boolean stop = false;
		for (XMPPPreprocessorIfc preproc: preProcessors.values()) {
			stop |= preproc.preProcess(packet, conn, naUserRepository, results);
		} // end of for (XMPPPreprocessorIfc preproc: preProcessors)


		if (!stop) {
			walk(packet, conn, packet.getElement(), results);
			setPermissions(conn, results);
		}

		if (!stop) {
			for (XMPPPostprocessorIfc postproc: postProcessors.values()) {
				postproc.postProcess(packet, conn, naUserRepository, results);
			} // end of for (XMPPPostprocessorIfc postproc: postProcessors)
		} // end of if (!stop)

		addOutPackets(results);

		if (!packet.wasProcessed()) {
			Packet error = null;
			if (stop) {
				error =	Authorization.SERVICE_UNAVAILABLE.getResponseMessage(packet,
					"Service not available.", true);
			} else {
				if (packet.getElemFrom() != null || conn != null) {
					error =	Authorization.FEATURE_NOT_IMPLEMENTED.getResponseMessage(packet,
							"Feature not supported yet.", true);
				} else {
					log.warning("Lost packet: " + packet.getStringData());
				} // end of else
			} // end of if (stop) else
			if (conn != null) {
				error.setTo(conn.getConnectionId());
			} // end of if (conn != null)
			if (error != null) {
				addOutPacket(error);
			}
		} // end of if (result) else
		else {
			log.info("Packet processed by: " + packet.getProcessorsIds().toString());
		} // end of else
	}

	private void setPermissions(XMPPResourceConnection conn,
		Queue<Packet> results) {
		Permissions perms = Permissions.NONE;
		if (conn != null) {
			perms = Permissions.LOCAL;
			if (conn.isAuthorized()) {
				perms = Permissions.AUTH;
				try {
					String id = conn.getUserId();
					if (isAdmin(id)) {
						perms = Permissions.ADMIN;
					}
				} catch (NotAuthorizedException e) {
					perms = Permissions.NONE;
				}
			}
		}
		for (Packet res: results) {
			res.setPermissions(perms);
		}
	}

	private void addOutPackets(Queue<Packet> packets) {
		for (Packet res: packets) {
			log.finest("Handling response: " + res.getStringData());
			addOutPacket(res);
		} // end of for ()
	}

	private boolean isAdmin(String jid) {
		for (String adm: admins) {
			if (adm.equals(JID.getNodeID(jid))) {
				return true;
			}
		}
		return false;
	}

	private boolean processAdmins(Packet packet) {
		final String to = packet.getElemTo();
		if (isInRoutings(to)) {
			// Yes this packet is for admin....
			log.finer("Packet for admin: " + packet.getStringData());
			for (String admin: admins) {
				log.finer("Sending packet to admin: " + admin);
				Packet admin_pac =
          new Packet((Element)packet.getElement().clone());
				admin_pac.getElement().setAttribute("to", admin);
				processPacket(admin_pac);
			} // end of for (String admin: admins)
			return true;
		} // end of if (isInRoutings(to))
		return false;
	}

	private XMPPSession getSession(String jid) {
		return sessionsByNodeId.get(JID.getNodeID(jid));
	}

	private XMPPResourceConnection getResourceConnection(String jid) {
		XMPPSession session = getSession(jid);
		if (session != null) {
			return session.getResourceConnection(jid);
		} // end of if (session != null)
		return null;
	}

	private String getConnectionId(String jid) {
		XMPPResourceConnection res = getResourceConnection(jid);
		if (res != null) {
			return res.getConnectionId();
		} // end of if (res != null)
		return null;
	}

	private void walk(final Packet packet,
		final XMPPResourceConnection connection, final Element elem,
		final Queue<Packet> results) {
		for (XMPPProcessorIfc proc: processors.values()) {
			String xmlns = elem.getXMLNS();
			if (xmlns == null) { xmlns = "jabber:client";	}
			if (proc.isSupporting(elem.getName(), xmlns)) {
				log.finest("XMPPProcessorIfc: "+proc.getClass().getSimpleName()+
					" ("+proc.id()+")"+"\n Request: "+elem.toString());
				proc.process(packet, connection, naUserRepository, results);
				packet.processedBy(proc.id());
			} // end of if (proc.isSupporting(elem.getName(), elem.getXMLNS()))
		} // end of for ()
		Collection<Element> children = elem.getChildren();
		if (children != null) {
			for (Element child: children) {
				walk(packet, connection, child, results);
			} // end of for (Element child: children)
		} // end of if (children != null)
	}

	private void processCommand(Packet pc) {
		log.finer(pc.getCommand().toString() + " command from: " + pc.getFrom());
		Element command = pc.getElement();
		switch (pc.getCommand()) {
		case STREAM_OPENED:
			// It might be existing opened stream after TLS/SASL authorization
			// If not, it means this is new stream
			XMPPResourceConnection connection =	connectionsByFrom.get(pc.getFrom());
			if (connection == null) {
				log.finer("Adding resource connection for: " + pc.getFrom());
				final String hostname = Command.getFieldValue(pc, "hostname");
				connection = new XMPPResourceConnection(pc.getFrom(),
					user_repository, auth_repository, this);
				if (hostname != null) {
					log.finest("Setting hostname " + hostname
						+ " for connection: " + connection.getConnectionId());
					connection.setDomain(hostname);
				} // end of if (hostname != null)
				else {
					connection.setDomain(getDefHostName());
				} // end of if (hostname != null) else
				connectionsByFrom.put(pc.getFrom(), connection);
			} else {
				log.finest("Stream opened for existing session, authorized: "
					+ connection.isAuthorized());
			} // end of else
			connection.setSessionId(Command.getFieldValue(pc, "session-id"));
			log.finest("Setting session-id " + connection.getSessionId()
				+ " for connection: " + connection.getConnectionId());
			break;
		case USER_STATUS:
			String user_jid = Command.getFieldValue(pc, "jid");
			String hostname = JID.getNodeHost(user_jid);
			String av = Command.getFieldValue(pc, "available");
			boolean available = !(av != null && av.equalsIgnoreCase("false"));
			if (available) {
				connection = connectionsByFrom.get(pc.getElemFrom());
				if (connection == null) {
					connection = new XMPPResourceConnection(pc.getElemFrom(),
						user_repository, auth_repository, this);
					connection.setDomain(hostname);
					// Dummy session ID, we might decide later to set real thing here
					connection.setSessionId("session-id");
					connectionsByFrom.put(pc.getElemFrom(), connection);
					handleLogin(JID.getNodeNick(user_jid), connection);
					connection.setResource(JID.getNodeResource(user_jid));
					Packet presence =
						new Packet(new Element("presence",
								new Element[] {new Element("priority", "-1")}, null, null));
					presence.setFrom(pc.getElemFrom());
					presence.setTo(pc.getTo());
					addOutPacket(presence);
				} else {
					log.finest("USER_STATUS set to true for user who is already available: "
						+ pc.toString());
				}
			} else {
				connection = connectionsByFrom.remove(pc.getElemFrom());
				if (connection != null) {
					closeSession(connection);
				} else {
					log.info("Can not find resource connection for packet: " +
						pc.toString());
				}
			}
			break;
		case GETFEATURES:
			if (pc.getType() == StanzaType.get) {
				List<Element> features =
					getFeatures(connectionsByFrom.get(pc.getFrom()));
				Packet result = pc.commandResult("result");
				Command.setData(result, features);
				addOutPacket(result);
			} // end of if (pc.getType() == StanzaType.get)
			break;
		case STREAM_CLOSED:
			log.fine("Stream closed from: " + pc.getFrom());
			++closedConnections;
			connection = connectionsByFrom.remove(pc.getFrom());
			if (connection != null) {
				closeSession(connection);
			} else {
				log.info("Can not find resource connection for packet: " +
					pc.toString());
			} // end of if (conn != null) else
			break;
		case OTHER:
			log.info("Other command found: " + pc.getStrCommand());
			break;
		default:
			break;
		} // end of switch (pc.getCommand())
	}

	private void closeSession(XMPPResourceConnection conn) {
		try {
			String userId = conn.getUserId();
			log.info("Closing connection for: " + userId);
			XMPPSession session = conn.getParentSession();
			if (session != null) {
				log.info("Found parent session for: " + userId);
				if (session.getActiveResourcesSize() <= 1) {
					session = sessionsByNodeId.remove(userId);
					if (session == null) {
						log.info("UPS can't remove session, not found in map: " + userId);
					} else {
						log.finer("Number of authorized connections: "
							+ sessionsByNodeId.size());
					} // end of else
				} else {
					log.finer("Number of connections is "
						+ session.getActiveResourcesSize() + " for the user: " + userId);
				} // end of else
			} // end of if (session.getActiveResourcesSize() == 0)
		} catch (NotAuthorizedException e) {
			log.info("Closed not authorized session: " + e);
		}
		Queue<Packet> results = new LinkedList<Packet>();
		for (XMPPStopListenerIfc stopProc: stopListeners.values()) {
			stopProc.stopped(conn, results);
		} // end of for ()
		for (Packet res: results) {
			log.finest("Handling response: " + res.getStringData());
			addOutPacket(res);
		} // end of for ()
		conn.streamClosed();
	}

	private XMPPResourceConnection getXMPPResourceConnection(Packet p) {
		if (p.getFrom() != null) {
			return connectionsByFrom.get(p.getFrom());
		}
		return null;
	}

	private XMPPSession getXMPPSession(Packet p) {
		return connectionsByFrom.get(p.getFrom()).getParentSession();
	}

	private List<Element> getFeatures(XMPPResourceConnection session) {
		List<Element> results = new LinkedList<Element>();
		for (XMPPProcessorIfc proc: processors.values()) {
			Element[] features = proc.supStreamFeatures(session);
			if (features != null) {
				results.addAll(Arrays.asList(features));
			} // end of if (features != null)
		} // end of for ()
		return results;
	}

	public Map<String, Object> getDefaults(Map<String, Object> params) {
		Map<String, Object> props = super.getDefaults(params);
		SessionManagerConfig.getDefaults(props, params);
		return props;
	}

	private void addComponent(String comp_id) {
		System.out.println("Loading component: " + comp_id + " ...");
		XMPPProcessorIfc proc = ProcessorFactory.getProcessor(comp_id);
		boolean loaded = false;
		if (proc != null) {
			processors.put(comp_id, proc);
			log.config("Added processor: " + proc.getClass().getSimpleName()
				+ " for component id: " + comp_id);
			loaded = true;
		}
		XMPPPreprocessorIfc preproc = ProcessorFactory.getPreprocessor(comp_id);
		if (preproc != null) {
			preProcessors.put(comp_id, preproc);
			log.config("Added preprocessor: " + preproc.getClass().getSimpleName()
				+ " for component id: " + comp_id);
			loaded = true;
		}
		XMPPPostprocessorIfc postproc = ProcessorFactory.getPostprocessor(comp_id);
		if (postproc != null) {
			postProcessors.put(comp_id, postproc);
			log.config("Added postprocessor: " + postproc.getClass().getSimpleName()
				+ " for component id: " + comp_id);
			loaded = true;
		}
		XMPPStopListenerIfc stoplist = ProcessorFactory.getStopListener(comp_id);
		if (stoplist != null) {
			stopListeners.put(comp_id, stoplist);
			log.config("Added stopped processor: " + stoplist.getClass().getSimpleName()
				+ " for component id: " + comp_id);
			loaded = true;
		}
		if (!loaded) {
			log.warning("No implementation found for component id: " + comp_id);
		} // end of if (!loaded)
	}

	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);

		Security.insertProviderAt(new TigaseSaslProvider(), 6);

		try {
			String cls_name = (String)props.get(USER_REPO_CLASS_PROP_KEY);
			String res_uri = (String)props.get(USER_REPO_URL_PROP_KEY);
			user_repository = RepositoryFactory.getUserRepository(cls_name, res_uri);
			log.config("Initialized " + cls_name + " as user repository: " + res_uri);
		} catch (Exception e) {
			log.severe("Can't initialize user repository: " + e);
			e.printStackTrace();
			System.exit(1);
		} // end of try-catch
		try {
			String cls_name = (String)props.get(AUTH_REPO_CLASS_PROP_KEY);
			String res_uri = (String)props.get(AUTH_REPO_URL_PROP_KEY);
			auth_repository =	RepositoryFactory.getAuthRepository(cls_name, res_uri);
			log.config("Initialized " + cls_name + " as auth repository: " + res_uri);
		} catch (Exception e) {
			log.severe("Can't initialize auth repository: " + e);
			e.printStackTrace();
			System.exit(1);
		} // end of try-catch

		naUserRepository = new NARepository(user_repository);
		String[] components = (String[])props.get(COMPONENTS_PROP_KEY);
		processors.clear();
		for (String comp_id: components) {
			addComponent(comp_id);
		} // end of for (String comp_id: components)
		String[] hostnames = (String[])props.get(HOSTNAMES_PROP_KEY);
		clearRoutings();
		for (String host: hostnames) {
			addRouting(host);
		} // end of for ()

		admins = (String[])props.get(ADMINS_PROP_KEY);

	}

	public void handleLogin(final String userName,
		final XMPPResourceConnection conn) {
		String userId = JID.getNodeID(userName, conn.getDomain());
		XMPPSession session = sessionsByNodeId.get(userId);
		if (session == null) {
			session = new XMPPSession(userName);
			sessionsByNodeId.put(userId, session);
		} // end of if (session == null)
		session.addResourceConnection(conn);
	}


	public void handleLogout(final String userName,
		final XMPPResourceConnection conn) {
		String userId = JID.getNodeID(userName, conn.getDomain());
		XMPPSession session = sessionsByNodeId.get(userId);
		if (session != null && session.getActiveResourcesSize() <= 1) {
			sessionsByNodeId.remove(userId);
		} // end of if (session.getActiveResourcesSize() == 0)
	}

	public List<Element> getDiscoFeatures(String node, String jid) {
		if (node == null) {
			List<Element> results = new LinkedList<Element>();
			for (XMPPProcessorIfc proc: processors.values()) {
				Element[] discoFeatures = proc.supDiscoFeatures(null);
				if (discoFeatures != null) {
					results.addAll(Arrays.asList(discoFeatures));
				} // end of if (discoFeatures != null)
			}
			results.addAll(Arrays.asList(DISCO_FEATURES));
			return results;
		}
		return null;
	}

	public List<Element> getDiscoItems(String node, String jid) {
		return null;
	}

	public List<StatRecord> getStatistics() {
		List<StatRecord> stats = super.getStatistics();
		stats.add(new StatRecord("Open connections", "int",
				connectionsByFrom.size()));
		stats.add(new StatRecord("Open authorized sessions", "int",
				sessionsByNodeId.size()));
// 		if (sessionsByNodeId.size() > 10) {
// 			System.out.println(sessionsByNodeId.toString());
// 		} // end of if (sessionsByNodeId.size() > 10)
		stats.add(new StatRecord("Closed connections", "long", closedConnections));
// 		stats.add(new StatRecord("UserAuthRepository implementation", "text",
// 				auth_repository.getClass().getSimpleName()));
// 		stats.add(new StatRecord("UserAuthRepository connection string", "text",
// 				auth_repository.getResourceUri()));
// 		stats.add(new StatRecord("UserRepository implementation", "text",
// 				user_repository.getClass().getSimpleName()));
// 		stats.add(new StatRecord("UserRepository connection string", "text",
// 				user_repository.getResourceUri()));
		return stats;
	}

	private class NARepository implements NonAuthUserRepository {

		UserRepository rep = null;

		NARepository(UserRepository userRep) {
			rep = userRep;
		}

		private String calcNode(String base, String subnode) {
			if (subnode == null) {
				return base;
			} // end of if (subnode == null)
			return base + "/" + subnode;
		}

		public String getPublicData(String user, String subnode, String key,
			String def)	throws UserNotFoundException {
			try {
				return rep.getData(user, calcNode(PUBLIC_DATA_NODE, subnode), key, def);
			}	catch (TigaseDBException e) {
				log.log(Level.SEVERE, "Problem accessing repository data.", e);
				return null;
			} // end of try-catch
		}

		public String[] getPublicDataList(String user, String subnode, String key)
			throws UserNotFoundException {
			try {
				return rep.getDataList(user, calcNode(PUBLIC_DATA_NODE, subnode), key);
			}	catch (TigaseDBException e) {
				log.log(Level.SEVERE, "Problem accessing repository data.", e);
				return null;
			} // end of try-catch
		}

		public void addOfflineDataList(String user, String subnode, String key,
			String[] list) throws UserNotFoundException {
			try {
				rep.addDataList(user, calcNode(OFFLINE_DATA_NODE, subnode), key, list);
			}	catch (TigaseDBException e) {
				log.log(Level.SEVERE, "Problem accessing repository data.", e);
			} // end of try-catch
		}

		public void addOfflineData(String user, String subnode, String key,
			String value) throws UserNotFoundException, DataOverwriteException {
			String node = calcNode(OFFLINE_DATA_NODE, subnode);
			try {
				String data = rep.getData(user,	node, key);
				if (data == null) {
					rep.setData(user,	node, key, value);
				} else {
					throw new
						DataOverwriteException("Not authorized attempt to overwrite data.");
				} // end of if (data == null) else
			}	catch (TigaseDBException e) {
				log.log(Level.SEVERE, "Problem accessing repository data.", e);
			} // end of try-catch
		}

	}

}
