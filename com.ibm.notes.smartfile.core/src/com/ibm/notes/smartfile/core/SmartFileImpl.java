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
 * ========================================================================== *
 * SmartFile - SwiftFile for the Rest Of Us.
 * <p>
 * SmartFile emulates the vector space model analysis performed by SwiftFile to
 * populate three SwiftFile folder name fields in the Lotus Notes e-mail Message 
 * form with recommendations for filing e-mail messages.  
 * <p>
 * The vector space model used here is based on the example provided by Dr E Garcia:
 * http://www.miislita.com/term-vector/term-vector-3.html.
 * <p>
 * Additional inspiration came from papers written by the SwiftFile authors, Richard
 * Segal and Jeffrey Kephart:
 * <br />http://www.research.ibm.com/swiftfile/dynlearn.pdf
 * <br />http://www.research.ibm.com/swiftfile/mailcat.pdf
 *
 * @author		David King <dlking@us.ibm.com
 * @version		1.3
 *
 *  by David King
 *  dlking@us.ibm.com
 *
 *  (C) Copyright IBM Corporation 2010, 2012
 *
 ****************************************************************************************************
 *
 *  2012-02-20 - Version 1.3
 *    - Handle case where the index is empty, resulting in no folder recommendations from
 *      processDocument
 *
 *  2011-11-29 - Version 1.2
 *    - Handle Notes 4000 errors while saving documents during learning
 *
 *  2011-09-30 - Version 1.0-3
 *    - Skip over deleted files (in Trash) while learning
 *
 ***************************************************************************************************/
package com.ibm.notes.smartfile.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.DocumentCollection;
import lotus.domino.NotesException;
import lotus.domino.NotesFactory;
import lotus.domino.NotesThread;
import lotus.domino.Session;
import lotus.domino.View;

public class SmartFileImpl extends NotesThread {

	// SmartFile version number string
	private static String version = "2.0";

	// Other parameters that come from the configuration file, maiFileName is
	// the only mandatory one
	private static Configuration config = null;
	private static String password = new String("");

	// A flag to signal that the vector model should be rebuilt
	private static boolean rebuildModel = false;

	// Flags that we use for command line arguements
	private static boolean forcePasswordPrompt = false;
	private static boolean deleteCorruptDocuments = false;

	// Remembering the database's last modification time between loops
	// Initialized to 0 to force processing once on program start
	private static Date priorModification = new Date(0);

	private static JFrame frame = new JFrame();

	private static Engine engine;

	public static void main(String argv[]) {

		// Read our configuration file and validate its contents
		if (!getConfig()) {
			return;
		}

		Utils.debugLog("SmartFile v" + version + " starting on "
				+ System.getProperty("os.name") + " ("
				+ ManagementFactory.getRuntimeMXBean().getName() + ")");

		// Process command line arguments
		if (argv.length > 0) {
			for (int iii = 0; iii < argv.length; iii++) {
				if (argv[iii].charAt(0) == '-') {
					if (argv[iii].equals("--delete-corrupt-documents")) {
						deleteCorruptDocuments = true;
						Utils.debugLog("\t--delete-corrupt-documents option invoked");
					} else if (argv[iii].equals("--force-password-prompt")) {
						forcePasswordPrompt = true;
						Utils.debugLog("\t--force-password-prompt option invoked");
					}
				}
			}
		}

		// Read the HashMaps from the persistence file
		rebuildModel = readPersistenceFile(config.getSmartfilePersistenceFile());

		// Slight delay on initial startup to avoid conflict with the Notes
		// client's login process
		// when we're started simultaneously with the Notes client (totally a
		// bad hack)
		try {
			Utils.debugLog("10 second delay waiting for Notes Client to start.");
			Thread.sleep(10000);
		} catch (Exception e) {
			e.printStackTrace();
			Utils.debugLog("Java Exception:", e);
		}

		// The big loop, run the thread with a two second delay between each run
		while (true) {

			// In case they erase the persistence file while we're running ...
			File f = new File(config.getSmartfilePersistenceFile());
			if (!f.exists()) {
				rebuildModel = true;
			} else {
				rebuildModel = false;
			}

			// If we don't have a password, or they're forcing us to ask for
			// one,
			// ask for one
			if ((password.length() == 0) || forcePasswordPrompt) {
				if (forcePasswordPrompt) {
					forcePasswordPrompt = false;
				}
				password = getNotesPassword();
				// If we aren't going to rebuild the model, then rewrite the
				// persistence file to get the new password in there
				if (!rebuildModel) {
					writePersistenceFile(config.getSmartfilePersistenceFile());
				}
			}

			// Start the thread
			SmartFileImpl t = new SmartFileImpl();
			t.start();

			try {
				// Wait for thread to finish, then sleep for 0.2 seconds
				t.join();
				Thread.sleep(200);
			} catch (Exception e) {
				e.printStackTrace();
				Utils.debugLog("Java Exception:", e);
				break;
			}

		}

		Utils.debugLog("Smartfile terminated");

	}

	// The thread
	public void runNotes() {

		Session s = null;

		try {

			// Connect to the database
			s = NotesFactory.createSession();
			Database db = s
					.getDatabase((String) null, config.getMailFileName());

			// If the persistence file didn't exist when we started or got
			// erased while we were running ...
			if (rebuildModel) {
				engine.rebuildModel(db);

				// Persist this model in a file
				writePersistenceFile(config.getSmartfilePersistenceFile());
			}

			// If the mail database has been modified ...
			if (db.getLastModified().timeDifference(
					s.createDateTime(priorModification)) > 0) {

				Utils.debugLog("Database modified after "
						+ db.getLastModified().timeDifference(
								s.createDateTime(priorModification))
						+ " seconds");

				// Save the modification time for the next loop - doing this
				// here means we always loop through the database
				// twice if we update any documents but it also makes sure that
				// we don't miss any changes made while we're
				// in the middle of processing the database
				priorModification = db.getLastModified().toJavaDate();

				// If FolderReferencesEnabled is not turned on, do that
				if (!db.getFolderReferencesEnabled()) {
					db.setFolderReferencesEnabled(true);
					Utils.debugLog("\tTurned on FolderReferences in the database");
				}

				// Walk through all of the documents in the Drafts folder and
				// set their SwiftFile fields if they aren't set already
				engine.setSFLabels(db, "($Drafts)");

				// Walk through all of the documents in the Inbox folder and
				// set their SwiftFile fields if they aren't set already
				engine.setSFLabels(db, "($Inbox)");

				// Walk through all of the rest of the documents in the database
				// looking for newly filed documents
				// and "learn" from those files
				boolean learned = false;

				DocumentCollection dc = db.getAllDocuments();
				if (dc.getCount() > 0) {
					Document doc = dc.getFirstDocument();
					while (doc != null) {
						Document newdoc = dc.getNextDocument();
						learned = this.checkOneDocumentForChanges(doc, learned);

						doc.recycle();
						doc = newdoc;
					}
				}

				boolean foldersChanged = false;
				if (!learned) {
					// We check folders only if we don't have to rebuild yet
					// Check the folder structure. If a folder has been deleted,
					// delete it from wordCounts

					ArrayList<String> newList = new ArrayList<String>();
					List<View> allViews = engine.getFoldersFromDB(db, newList);
					// We don't need the views
					for (View v : allViews) {
						Utils.shred(v);
					}
					ArrayList<String> oldList = engine.getFolderList();
					for (String vName : oldList) {
						if (newList.contains(vName)) {
							newList.remove(vName);
						} else {
							foldersChanged = true;
							break;
						}
					}

					if (newList.size() > 0) {
						foldersChanged = true;
					}
				}

				if (learned || foldersChanged) {
					// Build that sums again, rumbles through a lot
					// of linked lists
					engine.refeshWords();

					writePersistenceFile(config.getSmartfilePersistenceFile());
				}

			}

		} catch (NotesException e) {
			if (e.id == 6408) { // Incorrect password
				password = ""; // JOptionPane won't work inside the NotesThread
								// on OS X
				return;
			} else if (e.id == 6022) { // Userid file in use by another process,
										// so quit
				Utils.debugLog("The ID file is locked by another process, retrying login ...");
				return;
			} else {
				System.out.println("NotesException: " + e.id + " " + e.text);
				e.printStackTrace();
				Utils.debugLog(e.id + " " + e.text, e);
			}
		} catch (Exception e) {
			e.printStackTrace();
			Utils.debugLog("Java Exception:", e);
		} finally {
			try {
				if (s != null)
					s.recycle();
			} catch (NotesException e) {
				System.out.println("NotesException: " + e.id + " " + e.text);
				e.printStackTrace();
				Utils.debugLog(e.id + " " + e.text, e);
			}
		}
	}

	/*************************************************************************************************
	 * r e a d P e r s i s t e n c e F i l e
	 **************************************************************************************************/

	private static boolean readPersistenceFile(String filename) {
		try {

			// Read the vector model, and other values, from a file
			FileInputStream in = new FileInputStream(filename);
			engine.load(in);
			in.close();
			return false; // no need to rebuild

		} catch (FileNotFoundException e) {
			Utils.debugLog("\tUnable to find my vector model persistence file: "
					+ config.getSmartfilePersistenceFile());
			return true; // need to rebuild
		} catch (Exception e) {
			e.printStackTrace();
			Utils.debugLog("Java Exception in readPersistenceFile:", e);
			return true; // need to rebuild
		}
	}

	/*************************************************************************************************
	 * w r i t e P e r s i s t e n c e F i l e
	 **************************************************************************************************/

	private static void writePersistenceFile(String filename) {
		try {

			// Persist the vector model, and other values, in a file
			FileOutputStream out = new FileOutputStream(filename);
			engine.save(out);
			out.close();
			Runtime r = Runtime.getRuntime();
			r.exec("chmod 600 " + config.getSmartfilePersistenceFile());

		} catch (Exception e) {
			e.printStackTrace();
			Utils.debugLog("Java Exception in writePersistenceFile:", e);
		}

	}

	/*************************************************************************************************
	 * g e t C o n f i g
	 **************************************************************************************************/

	// Read program configuration data out of config file
	private static boolean getConfig() {

		String propertiesFilename = null;
		String osName = System.getProperty("os.name");
		if (osName.equals("Mac OS X")) {
			propertiesFilename = new String(
					System.getProperty("user.home")
							+ "/Library/Application Support/SmartFile/SmartFile.config.txt");
		} else {
			propertiesFilename = new String(System.getProperty("user.home")
					+ System.getProperty("file.separator") + ".smartfilerc");
		}

		try {
			config.load(new FileInputStream(propertiesFilename));
			// Save the log file
			Utils.setDebugLogFile(config.getDebugLogFile());
		} catch (IOException e) {
			popupError("Unable to load the configuration file: \""
					+ propertiesFilename + "\"\n"
					+ "FYI: System.getProperty(\"os.name\") = " + osName
					+ "\n Error:" + e.getMessage());
			return false;
		}

		// Gentlement start your engines
		engine = new Engine(config);

		return true;

	}

	/*************************************************************************************************
	 * g e t N o t e s P a s s w o r d
	 **************************************************************************************************/

	// Prompt user for their Lotus Notes password
	private static String getNotesPassword() {
		Utils.debugLog("Prompting for password with popup dialog");
		String password = "";
		ImageIcon icon = createImageIcon("images/keys.gif");
		JPasswordField passwordField = new JPasswordField(20);
		passwordField
				.setToolTipText("Enter your Lotus Notes password and click \"Ok\"");
		int action = JOptionPane.showConfirmDialog(frame, passwordField,
				"Notes Password for SmartFile", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.QUESTION_MESSAGE, icon);
		if (action >= 0) {
			password = new String(passwordField.getPassword());
		}
		return password;
	}

	protected static ImageIcon createImageIcon(String path) {
		java.net.URL imgURL = SmartFileImpl.class.getResource(path);
		if (imgURL != null) {
			return new ImageIcon(imgURL);
		} else {
			Utils.debugLog("Could not find the dialog icon file: " + path);
			return null;
		}
	}

	/*************************************************************************************************
	 * p o p u p E r r o r
	 **************************************************************************************************/

	private static void popupError(String message) {
		ImageIcon icon = createImageIcon("images/warning.gif");
		JOptionPane.showMessageDialog(frame, message, "SmartFile Error",
				JOptionPane.ERROR_MESSAGE, icon);
	}

	private boolean checkOneDocumentForChanges(Document doc, boolean oldStatus) {
		boolean result = false;

		// Learn from unprocessed files located in other folders
		// (probably recently moved into those folders)
		try {

			// Don't process documents that are in the Trash
			// (Version 1.0-3)
			if (!doc.isDeleted()) {

				// Get list of folders this document belongs to
				@SuppressWarnings("rawtypes")
				Vector refs = doc.getFolderReferences();

				// If it's a document we've seen before
				if (doc.hasItem("SmartFile")) {
					// Get the list of folders we think it's
					// supposed to be in
					@SuppressWarnings("rawtypes")
					Vector myFlag = doc.getItemValue("SmartFile");

					// If it still has its SFLabels field set or
					// it it isn't where we think it is
					if ((doc.getItemValueString("SFLabels").length() != 0)
							|| (!myFlag.equals(refs))) {
						// Learn from this document
						result = engine.learn(doc);
						if (result) {
							Utils.debugLog("\tLearning from document: \""
									+ doc.getUniversalID() + " - "
									+ doc.getItemValueString("Subject") + "\"");
							// Clear the SFLabel_ fields to flag
							// the fact that we've processed
							// this file
							doc.replaceItemValue("SFLabels", "");
							doc.replaceItemValue("SFLabel1", "");
							doc.replaceItemValue("SFLabel2", "");
							doc.replaceItemValue("SFLabel3", "");
							// Set SmartFile field to the
							// current folder(s) so that we can
							// recognize changes later.
							doc.replaceItemValue("SmartFile", refs);
							doc.save();
						}
					}
				} else {
					// Record where the document is currently
					// located
					doc.replaceItemValue("SmartFile", refs);
					doc.save();
				}

			}
		} catch (NotesException e) {
			try {
				if (e.id == 1028) {
					// There was a "Search" exception thrown by
					// doc.getFolderReferences()
					Utils.debugLog("Failed to retrieve folder references for doc: "
							+ doc.getUniversalID()
							+ " - "
							+ "Subject: "
							+ doc.getItemValueString("Subject") + "\"");
					if (deleteCorruptDocuments) {
						Utils.debugLog("\tThis document may be corrupt, we will delete it.");
						doc.remove(true);
					} else {
						Utils.debugLog("\tThis document may be corrupt.  Processing continues with next document.");
					}
				} else if (e.id == 4000) {
					// There was a field length exception
					Utils.debugLog("Unexpected Notes exception 4000 for doc: "
							+ doc.getUniversalID() + " - " + "Subject: "
							+ doc.getItemValueString("Subject") + "\"");
					if (deleteCorruptDocuments) {
						Utils.debugLog("\tThis document may be corrupt, we will delete it.");
						doc.remove(true);
					} else {
						Utils.debugLog("\tThis document may be corrupt.  Processing continues with next document.");
					}
					Utils.debugLog(e.id + " " + e.text, e);
				} else if (e.id != 549) {
					Utils.debugLog("Unexpected exception during learning process");
					Utils.debugLog("While processing doc: "
							+ doc.getUniversalID() + " - " + "Subject: "
							+ doc.getItemValueString("Subject") + "\"");
					Utils.debugLog(e.id + " " + e.text, e);
				}
			} catch (NotesException nasty) {
				nasty.printStackTrace();
			}
		}

		// Trap door boolean. if Oldstatus was true, it must be true in any case
		if (oldStatus) {
			return true;
		}
		return result;
	}

}
