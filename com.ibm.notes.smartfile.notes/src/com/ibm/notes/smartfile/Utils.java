/** ========================================================================= *
 * Copyright (C) 2009, 2014 IBM Corporation ( http://www.ibm.com/ )           *
 *                            All rights reserved.                            *
 *                                                                            *
 *  @author     David King <dlking@us.ibm.com>                                *
 *  @author     Stephan H. Wissel <st.wissel@sg.ibm.com>                      *   
 *                                                                            *
 * @version     1.0                                                           *
 * ========================================================================== *
 *                                                                            *
 * Licensed under the  Apache License, Version 2.0  (the "License").  You may *
 * not use this file except in compliance with the License.  You may obtain a *
 * copy of the License at <http://www.apache.org/licenses/LICENSE-2.0>.       *
 *                                                                            *
 * Unless  required  by applicable  law or  agreed  to  in writing,  software *
 * distributed under the License is distributed on an  "AS IS" BASIS, WITHOUT *
 * WARRANTIES OR  CONDITIONS OF ANY KIND, either express or implied.  See the *
 * License for the  specific language  governing permissions  and limitations *
 * under the License.                                                         *
 *                                                                            *
 * ========================================================================== */
package com.ibm.notes.smartfile;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import lotus.domino.Base;
import lotus.domino.Database;
import lotus.domino.DxlImporter;
import lotus.domino.NotesException;
import lotus.domino.Session;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

public class Utils {

	/**
	 * Creates a simple view with no hierarchy using DXL The regular function in
	 * Java/LotusScript doesn't allow to set
	 * showresponsehierarchy/showhierarchies to be set to FALSE as we need it
	 * here
	 * 
	 * @param db
	 *            the database to create it with
	 * @param viewName
	 *            : name of the view
	 */
	public static void createFlatViewFromDXL(Database db, String viewName,
			String selectionFormula) {
		Session s = null;
		DxlImporter dx = null;
		String newView = "<view extendlastcolumn=\"true\" name=\""
				+ viewName
				+ "\" showhierarchies=\"false\" showinmenu=\"false\" showmargin=\"true\" showresponsehierarchy=\"false\" unreadmarks=\"none\">"
				+ "<code event=\"selection\"><formula>"
				+ selectionFormula
				+ "</formula></code>"
				+ "<column itemname=\"UNID\" width=\"10\"><columnheader title=\"UNID\"></columnheader>"
				+ "<code event=\"value\"><formula>@Text(@DocumentUniqueID)</formula></code></column>"
				+ "<column hidedetailrows=\"false\" itemname=\"FolderRef\" width=\"10\"><columnheader title=\"Folders\"></columnheader>"
				+ "<code event=\"value\"><formula>@WhichFolders</formula></code></column></view>";

		try {
			s = db.getParent();
			dx = s.createDxlImporter();
			dx.setDesignImportOption(1);
			dx.setAclImportOption(0);
			dx.setExitOnFirstFatalError(false);
			dx.setReplicaRequiredForReplaceOrUpdate(false);
			dx.importDxl(newView, db);
		} catch (NotesException e) {
			logError(e);
		} finally {
			Utils.shred(dx);
		}

	}

	public static void logError(Exception e) {
		logSomething(e.getMessage(), IStatus.ERROR, e);
	}

	/**
	 * Log errors without an error object
	 * 
	 * @param theMessage
	 */
	public static void logError(String theMessage) {
		logError(theMessage, null);
	}

	/**
	 * Logs an error into the Eclipse error log
	 * 
	 * @param theMessage
	 * @param e
	 */
	public static void logError(String theMessage, Exception e) {
		logSomething(theMessage, IStatus.ERROR, e);
	}

	public static void logInfo(String theMessage) {
		logSomething(theMessage, IStatus.INFO, null);
	}

	public static void logWarning(String theMessage) {
		logSomething(theMessage, IStatus.WARNING, null);
	}

	// Debugging tool - print a one dimensional HashMap
	@SuppressWarnings("rawtypes")
	public static void printHashMap(HashMap map) {
		try {
			Set set = map.entrySet();
			Iterator it = set.iterator();
			while (it.hasNext()) {
				Map.Entry me = (Map.Entry) it.next();
				System.out.println(me.getKey() + ": " + me.getValue());
				debugLog(me.getKey() + ": " + me.getValue());
			}
		} catch (Exception e) {
			logError(e);
		}
	}

	// Debugging tool - print a two dimensional HashMap
	@SuppressWarnings("rawtypes")
	public static void printHashMap2D(HashMap map) {
		try {
			HashMap tmpmap = new HashMap();
			Set set = map.entrySet();
			Iterator it = set.iterator();
			while (it.hasNext()) {
				Map.Entry me = (Map.Entry) it.next();
				System.out.println(me.getKey());
				debugLog(me.getKey() + ": ");
				tmpmap = (HashMap) map.get(me.getKey());
				Set set2 = tmpmap.entrySet();
				Iterator it2 = set2.iterator();
				while (it2.hasNext()) {
					Map.Entry me2 = (Map.Entry) it2.next();
					System.out.println("\t" + me2.getKey() + ": "
							+ me2.getValue());
					debugLog("\t" + me2.getKey() + ": " + me2.getValue());
				}
			}
		} catch (Exception e) {
			logError(e);
		}
	}

	/**
	 * Recycles all Notes objects if they are still objects
	 * 
	 * @param morituri
	 *            Array of NotesObjects
	 */
	public static void shred(Base... morituri) {
		for (Base dead : morituri) {
			if (dead != null) {
				try {
					dead.recycle();
				} catch (NotesException e) {
					// They are dead, we don't care
				}
			}
		}

	}

	/*************************************************************************************************
	 * D e b u g g i n g
	 **************************************************************************************************/

	private static void debugLog(String theMessage) {
		logInfo(theMessage);
	}

	/**
	 * The common logging function
	 * 
	 * @param theMessage
	 *            The error/message
	 * @param theStatus
	 *            The Status (as int from IStatus)
	 * @param theException
	 *            optional the Exception
	 */
	private static void logSomething(String theMessage, int theStatus,
			Exception theException) {
		ILog logger = Activator.getDefault().getLog();
		if (theException == null) {
			logger.log(new Status(theStatus, Activator.PLUGIN_ID, theMessage));
		} else {
			logger.log(new Status(theStatus, Activator.PLUGIN_ID, theMessage,
					theException));
		}
	}

}
