/*  Package Jabber Server
 *  Copyright (C) 2001, 2002, 2003, 2004, 2005
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
package tigase.xmpp.impl;

import java.util.Queue;
import java.util.List;
import java.util.logging.Logger;
import tigase.server.Packet;
import tigase.server.Command;
import tigase.xml.Element;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.XMPPProcessor;
import tigase.xmpp.XMPPProcessorIfc;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.StanzaType;
import tigase.xmpp.Authorization;
import tigase.util.JID;
import tigase.util.ElementUtils;
import tigase.db.NonAuthUserRepository;

/**
 * XEP-0039: Statistics Gathering.
 * http://www.xmpp.org/extensions/xep-0039.html
 *
 * Created: Sat Mar 25 06:45:00 2006
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class JabberIqStats extends XMPPProcessor
	implements XMPPProcessorIfc {

  private static final Logger log =
    Logger.getLogger("tigase.xmpp.impl.JabberIqStats");

  protected static final String XMLNS = "http://jabber.org/protocol/stats";
	protected static final String ID = XMLNS;
  protected static final String[] ELEMENTS =
	{"query", "command"};
  protected static final String[] XMLNSS =
	{XMLNS, Command.XMLNS};
  protected static final Element[] DISCO_FEATURES =
	{new Element("feature",	new String[] {"var"},	new String[] {XMLNS})};

	public String id() { return ID; }

	public String[] supElements() { return ELEMENTS; }

  public String[] supNamespaces() { return XMLNSS; }

  public Element[] supDiscoFeatures(final XMPPResourceConnection session)
	{ return DISCO_FEATURES; }

	public void process(final Packet packet, final XMPPResourceConnection session,
		final NonAuthUserRepository repo, final Queue<Packet> results) {

		if (session == null) { return; }

		try {
			// Maybe it is message to admininstrator:
			String id = JID.getNodeID(packet.getElemTo());

			log.finest("Received packet: " + packet.getStringData());

			if (packet.isCommand()) {
				if (packet.getCommand() == Command.GETSTATS
					&& packet.getType() == StanzaType.result) {
					// Send it back to user.
					Element iq =
						ElementUtils.createIqQuery(session.getDomain(), session.getJID(),
							StanzaType.result, packet.getElemId(), XMLNS);
					Element query = iq.getChild("query");
					Element stats = Command.getData(packet, "statistics", null);
					query.addChildren(stats.getChildren());
					Packet result = new Packet(iq);
					result.setTo(session.getConnectionId());
					results.offer(result);
					log.finest("Sending result: " + result.getStringData());
					return;
				} else {
					return;
				}
			} // end of if (packet.isCommand()


			// If ID part of user account contains only host name
			// and this is local domain it is message to admin
			if (id == null || id.equals("")
				|| id.equalsIgnoreCase(session.getDomain())) {
				Packet result = Command.GETSTATS.getPacket(session.getJID(),
					session.getDomain(), StanzaType.get, packet.getElemId());
				results.offer(result);
				log.finest("Sending result: " + result.getStringData());
				return;
			}

			if (id.equals(session.getUserId())) {
				// Yes this is message to 'this' client
				Element elem = (Element)packet.getElement().clone();
				Packet result = new Packet(elem);
				result.setTo(session.getConnectionId());
				result.setFrom(packet.getTo());
				results.offer(result);
				log.finest("Sending result: " + result.getStringData());
			} else {
				// This is message to some other client so I need to
				// set proper 'from' attribute whatever it is set to now.
				// Actually processor should not modify request but in this
				// case it is absolutely safe and recommended to set 'from'
				// attribute
				Element el_res = (Element)packet.getElement().clone();
				// According to spec we must set proper FROM attribute
				el_res.setAttribute("from", session.getJID());
				Packet result = new Packet(el_res);
				results.offer(result);
				log.finest("Sending result: " + result.getStringData());
			} // end of else
		} catch (NotAuthorizedException e) {
      log.warning(
				"Received stats request but user session is not authorized yet: " +
        packet.getStringData());
			results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
					"You must authorize session first.", true));
		} // end of try-catch
	}

} // JabberIqStats
