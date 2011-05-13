/*
 * ServerListener.java
 * This file is part of Freemail, copyright (C) 2007, 2008 Dave Baker
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Iterator;

public abstract class ServerListener {
	protected ServerSocket sock;
	private final ArrayList /* of ServerHandler */ handlers;
	private final ArrayList /* of Thread */ handlerThreads;
	
	protected ServerListener() {
		handlers = new ArrayList();
		handlerThreads = new ArrayList();
	}
	
	/**
	 * Terminate the run method
	 */
	public void kill() {
		try {
			if (sock != null) sock.close();
		} catch (IOException ioe) {
			
		}
		// kill all our handlers too
		for (Iterator i = handlers.iterator(); i.hasNext(); ) {
			ServerHandler handler =(ServerHandler) i.next();
			handler.kill();
		}
	}
	
	/**
	 * Wait for all our client threads to terminate
	 */
	public void joinClientThreads() {
		for (Iterator i = handlerThreads.iterator(); i.hasNext(); ) {
			Thread t = (Thread)i.next();
			while (t != null) {
				try {
					t.join();
					t = null;
				} catch (InterruptedException ie) {
					
				}
			}
		}
	}
	
	protected void addHandler(ServerHandler hdlr, Thread thrd) {
		handlers.add(hdlr);
		handlerThreads.add(thrd);
	}
	
	protected void reapHandlers() {
		// clean up dead handlers...
		for (Iterator i = handlers.iterator(); i.hasNext(); ) {
			ServerHandler handler = (ServerHandler)i.next(); 
			if (!handler.isAlive()) {
				i.remove();
			}
		}
		
		// ...and threads...
		for (Iterator i = handlerThreads.iterator(); i.hasNext(); ) {
			Thread t = (Thread)i.next();
			if (!t.isAlive()) {
				i.remove();
			}
		}
	}
}
