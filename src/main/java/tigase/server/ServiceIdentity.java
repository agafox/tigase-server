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
package tigase.server;

import tigase.xml.Element;

/**
 * Describe class ServiceIdentity here.
 *
 *
 * Created: Sat Feb 10 13:34:54 2007
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class ServiceIdentity {

	private String category = null;
	private String type = null;
	private String name = null;

	/**
	 * Creates a new <code>ServiceIdentity</code> instance.
	 *
	 */
	public ServiceIdentity(String category, String type, String name) {
		this.category = category;
		this.type = type;
		this.name = name;
	}

	public String getCategory() {
		return category;
	}

	public String getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public Element getElement() {
		return new Element("identity",
			new String[] {"category", "type", "name"},
			new String[] {category, type, name});
	}

}
