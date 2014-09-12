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
 * ========================================================================== */
package com.ibm.notes.smartfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Vector;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.NotesException;
import lotus.domino.Session;
import lotus.domino.View;
import lotus.domino.ViewEntry;
import lotus.domino.ViewNavigator;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * The SmartFile Engine that learns the words and folder distribution
 * 
 * @author stw
 * 
 */
public class Engine {

	// The number of folders in the database including their UNID
	// Key = FolderName, Value = UNID
	private HashMap<String, String> folderList = new HashMap<String, String>();
	// Key = UNID, Value = FolderName
	private HashMap<String, String> folderRef = new HashMap<String, String>();

	// A two dimensional HashMap where word -> (folder name -> tf)
	// "tf" = term frequency = word count
	private HashMap<String, HashMap<String, Double>> wordCounts = new HashMap<String, HashMap<String, Double>>();

	// A one dimensional HashMap where word -> idf
	// "idf" = inverse document frequency = log(total_number_of folders /
	// number_of_folders_that_contain_this_word)
	private HashMap<String, Double> wordIDFs = new HashMap<String, Double>();

	// A two dimensional HashMap where word -> (folder name -> tf * idf)
	private HashMap<String, HashMap<String, Double>> wordTFIDFs = new HashMap<String, HashMap<String, Double>>();

	// A one dimensional HashMap where folder name -> vector length
	// "vector length" = sqrt( tf*idf[1]^2 + tf*idf[2]^2 + ... + tf*idf[n]^2 )
	// for each folder ("^2" means "squared")
	private HashMap<String, Double> wordVectorLengths = new HashMap<String, Double>();

	/**
	 * Track if the model database has been loaded
	 */
	private boolean modelLoaded = false;

	/**
	 * The settings from the preferences
	 */
	private Configuration config = null;

	/**
	 * The Engine can only be initialized when we have a configuration
	 * 
	 * @param config
	 */
	public Engine(Configuration config) {
		this.config = config;
	}

	/**
	 * Save it into the Eclipse directory?
	 */
	public void save() {

		OutputStream out;
		try {
			out = new FileOutputStream(
					this.config.getSmartfilePersistenceFile());
			// Persist the vector model, and other values
			// goes into the workspace directory for the file
			ObjectOutputStream oos = new ObjectOutputStream(out);
			oos.writeObject(folderList);
			oos.writeObject(wordCounts);
			oos.writeObject(wordIDFs);
			oos.writeObject(wordTFIDFs);
			oos.writeObject(wordVectorLengths);
			oos.close();
			out.close();
		} catch (IOException e) {
			// We don't consider the model to be loaded if something fails here
			Utils.logError(e.getMessage(), e);

			// We don't consider the model to be loaded if something fails here
			this.modelLoaded = false;

		}

		return;

	}

	/**
	 * The scheduled processing routine is triggered by startup, replication (or
	 * any other event when we suspect the folders need update) It kicks of all
	 * work necessary by the engine
	 * 
	 * @param s
	 * @param monitor
	 */
	public IStatus scheduledProcessing(Session s, IProgressMonitor monitor) {

		IStatus result = null;
		Database mail = null;
		View v = null;
		ViewEntry ve = null;
		ViewEntry ven = null;
		Document doc = null;
		ViewNavigator n = null;

		Configuration config = Activator.getDefault().getConfig();

		// point to the current mail file
		String mailFileName = config.getMailFileName(s);

		if (mailFileName == null || mailFileName.equals("")
				|| !config.isEnabled()) {
			Utils.logError("Mailfile came back empty!");
			result = Status.CANCEL_STATUS;
			return result; // Early exit
		}

		Engine engine = Activator.getDefault().getEngine();

		try {
			mail = s.getDatabase("", mailFileName, true);

			// Make sure we have everything in place
			engine.checkDatabaseConditions(mail);

			// Load the existing persistence file
			try {
				engine.load();
			} catch (Exception e) {
				Utils.logError(e);
				// Loading didn't work, so we need to start from scratch
				// true means: save the model
				engine.rebuildModel(mail, monitor, true);
			}

			// Now process the folders and work on documents that
			// have been Changed to update the model
			List<String> foldersToProcess = new ArrayList<String>();
			foldersToProcess.add("($Inbox)");
			foldersToProcess.add("($Drafts)");
			this.setSFLabels(mail, foldersToProcess);

			// Now check all documents that have been filed elsewhere
			// and not been captured in the model yet
			// and "learn" from those files
			boolean learned = false;

			v = mail.getView(Configuration.SMARTFILE_VIEW);
			n = v.createViewNav();
			ve = n.getFirstDocument();

			while (ve != null) {
				ven = n.getNextDocument();
				doc = ve.getDocument();

				learned = this.checkOneDocumentForChanges(doc, learned);

				doc.recycle();
				ve.recycle();
				ve = ven;
			}

			boolean foldersChanged = false;
			if (!learned) {
				// We check folders only if we don't have to rebuild yet
				// Check the folder structure. If a folder has been deleted,
				// delete it from wordCounts
				HashMap<String, String> newFolderList = new HashMap<String, String>();
				HashMap<String, String> newFolderRef = new HashMap<String, String>();
				List<View> allViews = this.getFoldersFromDB(mail,
						newFolderList, newFolderRef);

				// We don't need the views we are only interested in the
				// newFolderList
				for (View v2 : allViews) {
					Utils.shred(v2);
				}

				for (Map.Entry<String, String> oldFolder : this.folderList
						.entrySet()) {
					if (newFolderList.containsKey(oldFolder.getKey())) {
						newFolderList.remove(oldFolder.getKey());
					} else {
						foldersChanged = true;
						break;
					}
				}

				if (newFolderList.size() > 0) {
					foldersChanged = true;
				}
			}

			if (learned || foldersChanged) {
				// Build that sums again, rumbles through a lot
				// of linked lists
				this.refeshWords(monitor);
				this.save();
			}

			// If we got here everything worked
			result = Status.OK_STATUS;

		} catch (NotesException ne) {
			Utils.logError(ne);
			result = Status.CANCEL_STATUS;
		} finally {
			// Cleanup
			Utils.shred(v, mail, ve, ven, doc, n);
		}

		return result;

	}

	/**
	 * Adds the values of the second hash map to the first one if keys exist,
	 * the value gets incremented, if keys not exist they get added
	 * 
	 * @param result
	 *            the merged/added hashmap
	 * @param newVals
	 *            the hashmap with values to be added
	 */
	private void addHashMapValues(HashMap<String, Double> result,
			HashMap<String, Double> newVals) {

		for (Map.Entry<String, Double> newEntry : newVals.entrySet()) {
			String curKey = newEntry.getKey();
			Double curVal = newEntry.getValue();
			if (result.containsKey(curKey)) {
				Double oldVal = result.get(curKey);
				Double newVal = new Double(curVal.doubleValue()
						+ oldVal.doubleValue());
				result.put(curKey, newVal);
			} else {
				result.put(curKey, curVal);
			}
		}
	}

	/*************************************************************************************************
	 * c a l c u l a t e I D F s
	 **************************************************************************************************/

	// Do the term weighting calculations for each word and return them as a one
	// dimensional HashMap
	private HashMap<String, Double> calculateIDFs(
			HashMap<String, HashMap<String, Double>> wordCounts,
			IProgressMonitor monitor) {

		monitor.subTask("calcuate IDFs");

		HashMap<String, Double> idfmap = new HashMap<String, Double>();

		for (Map.Entry<String, HashMap<String, Double>> me : wordCounts
				.entrySet()) {
			String curKey = me.getKey();
			HashMap<String, Double> tmpmap = me.getValue();

			// Get the number of folders that contain this word
			int df = tmpmap.size();

			// TODO: how does the folderlist get populated?
			// Calculate the IDF (inverse document frequency) for this word
			double idf = Math
					.log((double) this.folderList.size() / (double) df);

			// Store it in the HashMap
			idfmap.put(curKey, new Double(idf));

		}

		// TODO: better process monitor
		monitor.internalWorked(10);

		return idfmap;
	}

	/*************************************************************************************************
	 * c a l c u l a t e T F I D F s
	 **************************************************************************************************/

	// For each word, in each folder, calculate tf*idf and return a two
	// dimensional HashMap
	// where word -> (folder -> tf*idf)
	private HashMap<String, HashMap<String, Double>> calculateTFIDFs(
			HashMap<String, HashMap<String, Double>> wordCounts,
			HashMap<String, Double> wordIDFs, IProgressMonitor monitor) {
		HashMap<String, HashMap<String, Double>> tfidfmap = new HashMap<String, HashMap<String, Double>>();

		monitor.subTask("calculate TFIDs");

		for (Map.Entry<String, HashMap<String, Double>> me : wordCounts
				.entrySet()) {
			// Map Entries
			String curKey = me.getKey();
			HashMap<String, Double> tmpmap = me.getValue();
			// X-REF
			Double idf = wordIDFs.get(curKey);
			HashMap<String, Double> newmap = new HashMap<String, Double>();
			for (Map.Entry<String, Double> me2 : tmpmap.entrySet()) {
				String key2 = me2.getKey();
				Double count = me2.getValue();
				double tfidf = count.doubleValue() * idf.doubleValue();
				newmap.put(key2, new Double(tfidf));
			}
			tfidfmap.put(curKey, newmap);
		}
		// TODO: better process monitor
		monitor.internalWorked(10);

		return tfidfmap;
	}

	/*************************************************************************************************
	 * c a l c u l a t e V e c t o r L e n g t h s
	 **************************************************************************************************/

	// Calculate the vector length for each folder
	private HashMap<String, Double> calculateVectorLengths(
			HashMap<String, HashMap<String, Double>> wordTFIDFs,
			IProgressMonitor monitor) {

		monitor.subTask("calculate Vector length");

		HashMap<String, Double> vectorLengths = new HashMap<String, Double>();

		for (Map.Entry<String, HashMap<String, Double>> me : wordTFIDFs
				.entrySet()) {
			HashMap<String, Double> tmpmap = me.getValue();

			for (Map.Entry<String, Double> me2 : tmpmap.entrySet()) {
				String key2 = me2.getKey();
				double tfidf = me2.getValue().doubleValue();
				if (vectorLengths.containsKey(key2)) {
					double accum = vectorLengths.get(key2).doubleValue();
					accum += (tfidf * tfidf);
					vectorLengths.put(key2, new Double(accum));
				} else {
					vectorLengths.put(key2, new Double(tfidf * tfidf));
				}
			}
		}

		// Calculate the square root of the sum of the squares for each folder,
		// this is the vector length for the folder.
		HashMap<String, Double> newmap = new HashMap<String, Double>();
		for (Map.Entry<String, Double> me : vectorLengths.entrySet()) {
			String curKey = me.getKey();
			double curVal = me.getValue().doubleValue();
			newmap.put(curKey, new Double(Math.sqrt(curVal)));
		}
		// TODO: better process monitor
		monitor.internalWorked(10);
		return newmap;
	}

	/**
	 * Checks the preconditions for a given database To work and for performance
	 * we watch out for - Folder Reference enabled - View that compares the
	 * FolderRef with the SmartFolder entries This saves the need to scan all of
	 * the database and is more efficient than going through $All
	 * 
	 * @param db
	 */

	private void checkDatabaseConditions(Database db) {

		checkforFolderReference(db);
		checkforSmartFileView(db);

	}

	private void checkforFolderReference(Database db) {
		// If FolderReferencesEnabled is not turned on, do that
		try {
			if (!db.getFolderReferencesEnabled()) {
				db.setFolderReferencesEnabled(true);
				Utils.logInfo("\tTurned on FolderReferences in the database");
			}
		} catch (NotesException e) {
			Utils.logError(e);
		}
	}

	private void checkforSmartFileView(Database db) {
		View v = null;

		try {
			v = db.getView(Configuration.SMARTFILE_VIEW);
			if (v != null) {
				String selectionFormula = v.getSelectionFormula();
				if (!selectionFormula.equals(this
						.getSmartFileSelectionFormula())) {
					v.setSelectionFormula(this.getSmartFileSelectionFormula());
				}
			} else {
				// We need to create a view from DXL since we need one that is
				// FLAT
				// and does not show categories
				Utils.createFlatViewFromDXL(db, Configuration.SMARTFILE_VIEW,
						this.getSmartFileSelectionFormula());
			}

		} catch (NotesException e) {
			Utils.logError(e);
		} finally {
			Utils.shred(v);
		}

	}

	private boolean checkOneDocumentForChanges(Document doc, boolean oldStatus) {
		boolean result = false;

		// Learn from unprocessed files located in other folders
		// (probably recently moved into those folders)
		try {

			// Leave deleted documents alone
			if (doc.isDeleted()) {
				return oldStatus;
			}

			// Get list of folders this document belongs to
			@SuppressWarnings("rawtypes")
			Vector refs = doc.getFolderReferences();

			// If it's a document we've seen before
			if (doc.hasItem(Configuration.SMARTFILE_ITEMNAME)) {
				// Get the list of folders we think it's
				// supposed to be in
				@SuppressWarnings("rawtypes")
				Vector myFlag = doc
						.getItemValue(Configuration.SMARTFILE_ITEMNAME);

				// If it still has its SFLabels field set or
				// it it isn't where we think it is
				if ((doc.getItemValueString(Configuration.SFLABELS_FIELD)
						.length() != 0) || (!myFlag.equals(refs))) {
					// Learn from this document
					result = this.learn(doc);
					if (result) {
						Utils.logInfo("\tLearning from document: \""
								+ doc.getUniversalID() + " - "
								+ doc.getItemValueString("Subject") + "\"");
						// Clear the SFLabel_ fields to flag
						// the fact that we've processed
						// this file
						doc.replaceItemValue(Configuration.SFLABELS_FIELD, "");
						// TODO: externalise string
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
				// Record where the document is
				// currently located
				doc.replaceItemValue(Configuration.SMARTFILE_ITEMNAME, refs);
				doc.save();
			}

		} catch (NotesException e) {
			// TODO: more detailed error handling - see original code
			Utils.logError(e);
		}

		// Trap door boolean. if Oldstatus was true, it must be true in any case
		if (oldStatus) {
			return true;
		}
		return result;
	}

	/*************************************************************************************************
	 * c o u n t W o r d s
	 **************************************************************************************************/
	// Count all the words in each of the folders we care about and return those
	// the folders are provided as a List, so selection of folders needs to
	// happen outside this function!
	// counts in a two dimensional HashMap
	// where word -> (folder name -> tf)
	// tf = term frequesncy = word count
	private HashMap<String, HashMap<String, Double>> countWordsInDatabase(
			List<View> views, Database db, IProgressMonitor monitor) {

		// Holds the result for the word count
		HashMap<String, HashMap<String, Double>> totalCounts = new HashMap<String, HashMap<String, Double>>();

		// Holds all the document that don't have a folder reference yet (should
		// be empty after the
		// first run and the folderreference activation
		// UNID -> Foldernames
		HashMap<String, List<String>> docsWithoutFolderRef = new HashMap<String, List<String>>();

		Document doc = null;
		Document nextDoc = null;

		for (View v : views) {
			try {
				monitor.subTask("Processing " + v.getName());

				HashMap<String, Double> folderCount = new HashMap<String, Double>();

				// We need the docs, so we can skip the viewentrycollection
				doc = v.getFirstDocument();

				while (doc != null) {
					nextDoc = v.getNextDocument(doc);

					HashMap<String, Double> docCount = this
							.extractWordsFromDocument(doc);
					this.addHashMapValues(folderCount, docCount);

					// We need to process this document later on
					// We only can do that after all the folders have
					// been processed since we would not catch if it was
					// in a second folder
					if (!doc.hasItem(Configuration.FOLDER_REF)) {
						List<String> docFolders;
						String unid = doc.getUniversalID();
						if (!docsWithoutFolderRef.containsKey(unid)) {
							docFolders = new ArrayList<String>();
						} else {
							docFolders = docsWithoutFolderRef.get(unid);
						}
						docFolders.add(v.getName());
						docsWithoutFolderRef.put(unid, docFolders);
					}

					Utils.shred(doc);
					doc = nextDoc;
				}

				for (Map.Entry<String, Double> folderEntry : folderCount
						.entrySet()) {
					String curKey = folderEntry.getKey();
					HashMap<String, Double> curMap = null;

					if (totalCounts.containsKey(curKey)) {
						curMap = totalCounts.get(curKey);
					} else {
						curMap = new HashMap<String, Double>();
					}
					// Capture that this folder contains this word
					curMap.put(v.getName(), folderEntry.getValue());
					totalCounts.put(curKey, curMap);
				}

			} catch (NotesException e) {
				Utils.logError(e.id + " " + e.text, e);
			}

			// Update the process monitor
			monitor.internalWorked(1);

		}

		// We have 20 working units for the move to folder
		// so we report when dCount / processChunks = 0
		int dCount = docsWithoutFolderRef.size();
		dCount = dCount - (dCount % 20);
		int processChunks = dCount / 20;

		// Now we need to move all documents into the folders to update the
		// $FolderRef for the missing documents
		for (Map.Entry<String, List<String>> curDocEntry : docsWithoutFolderRef
				.entrySet()) {
			String unid = curDocEntry.getKey();
			List<String> folders2Move = curDocEntry.getValue();
			try {
				doc = db.getDocumentByUNID(unid);
				for (String f : folders2Move) {
					doc.putInFolder(f);
				}
			} catch (NotesException e) {
				Utils.logError(e);
			} finally {
				Utils.shred(doc);
			}

			// Monitor update
			if (processChunks == 0) {
				monitor.internalWorked(1); // We have less than 20 documents
											// here
			} else if (dCount > 0 && (dCount % processChunks == 0)) {
				monitor.internalWorked(1); // We report in respective chunks
			}
			dCount--;

		}

		return totalCounts;
	}

	/**
	 * Takes a document and counts all the words except the words in the
	 * Stopword list. Returns a hashmap with the words as keys and the count of
	 * each word as value. This is the base to compute proximity
	 * 
	 * @param doc
	 *            the document to be processed
	 * @return HashMap with Word -> CountInDocument
	 */
	private HashMap<String, Double> extractWordsFromDocument(Document doc) {
		// Count the words in this document,
		// adding those to the wordCounts HashMap
		HashMap<String, Double> tf = new HashMap<String, Double>();

		String language = this.getLanguageFromDocument(doc);

		Scanner s = getScannerFromDocument(doc);

		while (s.hasNext()) {
			String w = s.next().toLowerCase();
			w = w.replaceAll("\\W*$", ""); // remove trailing non-word
			// characters
			w = w.replaceAll("^\\W*", ""); // remove leading non-word
			// characters
			w = w.trim(); // remove leading and trailing whitespace
			if ((w.length() > 1) && (!config.isStopWord(w, language))) {
				if (tf.containsKey(w)) {
					Double ctr = tf.get(w);
					ctr = new Double(ctr.doubleValue() + 1);
					tf.put(w, ctr);
				} else {
					tf.put(w, new Double(1));
				}
			}
		}

		return tf;
	}

	/**
	 * public ArrayList<String> getFolderList() { return folderList; }
	 */

	private List<View> getFoldersFromDB(Database db,
			HashMap<String, String> folderNames,
			HashMap<String, String> folderRef) {
		// Provides only Folders that are not excluded
		List<View> result = null;
		String unid = null;
		String vName = null;

		try {
			@SuppressWarnings("rawtypes")
			Vector allViews = db.getViews();
			result = new ArrayList<View>(allViews.size());

			for (Object x : allViews) {
				View v = (View) x;
				// IsExcludedFolder returns true for any VIEW too
				if (this.config.isExcludedFolder(v.getName())) {
					Utils.shred(v);
				} else {
					result.add(v);
					if (folderNames != null) {
						vName = v.getName();
						unid = v.getUniversalID();
						folderNames.put(vName, unid);
						folderRef.put(unid, vName);
					}
				}
			}

		} catch (NotesException e) {
			Utils.logError(e);
		}
		return result;
	}

	private String getLabelWithOffset(int base) {
		return "SFLabel" + String.valueOf(base + 1);
	}

	/**
	 * Determines the language of a document to pick the right Stopword list for
	 * processing
	 * 
	 * @param doc
	 * @return
	 */
	private String getLanguageFromDocument(Document doc) {
		// TODO check with development how to figure the language
		// for now just support the default language
		return config.getDefaultLanguage();
	}

	private Scanner getScannerFromDocument(Document doc) {
		StringBuilder builder = new StringBuilder();

		// First all fields that can't have spaces like From, To etc
		for (String curFieldNoSpaces : config.getFieldsToProcessNoSpaces()) {
			try {
				if (doc.hasItem(curFieldNoSpaces)) {
					@SuppressWarnings("rawtypes")
					Vector values = doc.getItemValue(curFieldNoSpaces);
					for (int i = 0; i < values.size(); i++) {
						builder.append(values.elementAt(i).toString()
								.replace(" ", "_"));
						builder.append(" ");
					}
				}
			} catch (NotesException e) {
				// We don't care if that doesn't work for on element
			}
		}

		// Now the as-is fields
		for (String curFieldNoSpaces : config.getFieldsToProcess()) {
			try {
				if (doc.hasItem(curFieldNoSpaces)) {
					builder.append(doc.getItemValueString(curFieldNoSpaces));
					builder.append(" ");
				}
			} catch (NotesException e) {
				// We don't care if that doesn't work for on element
			}
		}

		Scanner s = new Scanner(builder.toString());
		return s;
	}

	/**
	 * The formula that shows all documents that have been moved around since we
	 * worked on them with SmartFile the last time SmartFile ran
	 * 
	 * @return The formula to select all the unprocessed documents
	 */
	private String getSmartFileSelectionFormula() {
		return "@Trim(@Replace(@Text(" + Configuration.FOLDER_REF + ");"
				+ Configuration.SMARTFILE_REFNAME + ");\"\")) != \"\"";
	}

	/*************************************************************************************************
	 * l e a r n
	 ************************************************************************************************** 
	 * 
	 * Processed one document and adds to the Engine's knowledge
	 * 
	 */

	private boolean learn(Document doc) {

		boolean learned = false;

		try {

			HashMap<String, Double> tf = extractWordsFromDocument(doc);

			@SuppressWarnings("rawtypes")
			Vector refs = doc.getFolderReferences();
			@SuppressWarnings("rawtypes")
			Vector myFlag = doc.getItemValue("SmartFile");

			// If this document has been processed before but it isn't were we
			// think it should be
			if ((doc.getItemValueString("SFLabels").length() == 0)
					&& (!myFlag.equals(refs))) {
				// Subtract this document's word counts from the wordCount
				// HashMap for the "SFLabels" folders
				for (int i = 0; i < myFlag.size(); i++) {
					String folder = (String) myFlag.elementAt(i);
					if (!config.isExcludedFolder(folder)) {
						learned = true;

						for (Map.Entry<String, Double> me : tf.entrySet()) {
							String curWord = me.getKey();
							Double value = me.getValue();
							HashMap<String, Double> tmpmap = null;

							if (this.wordCounts.containsKey(curWord)) {
								tmpmap = this.wordCounts.get(curWord);
							} else {
								tmpmap = new HashMap<String, Double>();
							}
							// If that HashMap has an entry for this folder
							if (tmpmap.containsKey(folder)) {
								// Subtract the word's count from it
								Double accum = tmpmap.get(folder);
								accum = new Double(accum.doubleValue()
										- value.doubleValue());
								// If that leaves anything
								if (accum.doubleValue() > 0) {
									// Put the new count into the map
									tmpmap.put(folder, accum);
								} else {
									// Otherwise remove the entry for this
									// folder from the map
									tmpmap.remove(folder);
								}
							}

							// If the entire contents of the folder map have
							// been deleted
							if (tmpmap.isEmpty()) {
								// Remove this word from wordCounts
								this.wordCounts.remove(curWord);
							} else {
								// Otherwise, store the updated map
								this.wordCounts.put(curWord, tmpmap);
							}
						}
					}
				}
			}

			// Add the counts for this document to the wordCounts HashMap for
			// the "refs" folders
			for (int i = 0; i < refs.size(); i++) {
				String folder = (String) refs.elementAt(i);
				if (!config.isExcludedFolder(folder)) {
					learned = true;

					for (Map.Entry<String, Double> me : tf.entrySet()) {
						String curWord = me.getKey();
						Double curValue = me.getValue();
						HashMap<String, Double> tmpmap = null;

						if (this.wordCounts.containsKey(curWord)) {
							tmpmap = this.wordCounts.get(curWord);
						} else {
							tmpmap = new HashMap<String, Double>();
						}
						double accum = curValue.doubleValue();
						if (tmpmap.containsKey(folder)) {
							accum += tmpmap.get(folder).doubleValue();
						}
						tmpmap.put(folder, new Double(accum));
						this.wordCounts.put(curWord, tmpmap);
					}
				}
			}

		} catch (NotesException e) {
			System.out.println("NotesException: " + e.id + " " + e.text);
			Utils.logError(e.id + " " + e.text, e);
		} catch (Exception e) {
			Utils.logError("Java Exception in learn:", e);
		}

		return learned;

	}

	@SuppressWarnings("unchecked")
	private void load() throws IOException, ClassNotFoundException {

		// Loading the model is slow, so we avoid if possible
		if (!this.modelLoaded) {

			File inFile = new File(this.config.getSmartfilePersistenceFile());

			InputStream in = new FileInputStream(inFile);

			// Read the vector model, and other values, from a file
			ObjectInputStream ois = new ObjectInputStream(in);
			this.folderList = (HashMap<String, String>) ois.readObject();
			this.wordCounts = (HashMap<String, HashMap<String, Double>>) ois
					.readObject();
			this.wordIDFs = (HashMap<String, Double>) ois.readObject();
			this.wordTFIDFs = (HashMap<String, HashMap<String, Double>>) ois
					.readObject();
			this.wordVectorLengths = (HashMap<String, Double>) ois.readObject();
			ois.close();

			this.modelLoaded = true;

		}
		return;

	}

	/*************************************************************************************************
	 * p r o c e s s D o c u m e n t
	 **************************************************************************************************/

	// Analyze a document and set the three SwiftFile fields to the folders that
	// most closely match
	private void processDocument(Document doc,
			HashMap<String, Double> wordIDFs,
			HashMap<String, HashMap<String, Double>> wordTFIDFs,
			HashMap<String, Double> wordVectorLengths) {
		try {
			// Parse out the individual words and accumulate their counts (tf)
			// in a HashMap
			HashMap<String, Double> tf = this.extractWordsFromDocument(doc);

			// Calculate tf * idf for each word in the document and save those
			// in a HashMap
			// Also sum their squares and calculate the vector length for the
			// document
			HashMap<String, Double> tfidfMap = new HashMap<String, Double>();
			double accum = 0;

			// For each word in the document ...
			for (Map.Entry<String, Double> me : tf.entrySet()) {
				String curWord = me.getKey();
				Double curCount = me.getValue();

				Double idf = null;
				// Get the matching IDF from the wordIDFs HashMap ...
				if (wordIDFs.containsKey(curWord)) {
					idf = wordIDFs.get(curWord);
				} else {
					idf = new Double(0);
				}
				// Calculate tf * idf and save it in a HashMap for later ...
				Double tfidf = new Double(curCount.doubleValue()
						* idf.doubleValue());
				tfidfMap.put(curWord, tfidf);

				// Sum the square ...
				accum += tfidf.doubleValue() * tfidf.doubleValue();
			}

			double docVectorLength = Math.sqrt(accum);

			// Calculate the dot products for each folder
			HashMap<String, Double> dotProductsByFolder = new HashMap<String, Double>();

			// For each word in the document ...
			for (Map.Entry<String, Double> me : tfidfMap.entrySet()) {
				String curKey = me.getKey();
				Double docTFIDF = me.getValue();
				// Get the matching folderName -> tf*idf HashMap from wordTFIDFs
				if (wordTFIDFs.containsKey(curKey)) {
					HashMap<String, Double> tmpmap = wordTFIDFs.get(curKey);
					// For each Folder in that map ...
					for (Map.Entry<String, Double> me2 : tmpmap.entrySet()) {
						String key2 = me2.getKey();
						Double folderTFIDF = me2.getValue();

						if (dotProductsByFolder.containsKey(key2)) {
							accum = dotProductsByFolder.get(key2).doubleValue();
						} else {
							accum = 0;
						}

						dotProductsByFolder.put(
								key2,
								new Double(accum
										+ (folderTFIDF.doubleValue() * docTFIDF
												.doubleValue())));
					}
				}
			}

			// Calculate the similarity values for each folder. Find the top
			// three. These will be the recommended folders.
			double[] sim = { 0, 0, 0 };
			String[] folder = { "", "", "" };

			// For each folder
			for (Map.Entry<String, Double> me : dotProductsByFolder.entrySet()) {
				String curKey = me.getKey();
				double dotProduct = me.getValue().doubleValue();
				double folderVectorLenght = wordVectorLengths.get(curKey)
						.doubleValue();
				// This is the key!
				double simValue = dotProduct / docVectorLength
						* folderVectorLenght;
				// Final comparison
				if (simValue > sim[2]) {
					sim[2] = simValue;
					folder[2] = curKey;
					if (simValue > sim[1]) {
						sim[2] = sim[1];
						folder[2] = folder[1];
						sim[1] = simValue;
						folder[1] = curKey;
						if (simValue > sim[0]) {
							sim[1] = sim[0];
							folder[1] = folder[0];
							sim[0] = simValue;
							folder[0] = curKey;
						}
					}
				}
			}

			// Set the document's SwiftFile fields
			Vector<String> sflabels = new Vector<String>();

			for (int i = 0; i < 3; i++) {
				if (folder[i] != null && folder[i].length() != 0) {
					sflabels.add(folder[i]);
					doc.replaceItemValue(this.getLabelWithOffset(i), folder[i]);
				}
			}

			if (sflabels != null && sflabels.size() != 0) {
				doc.replaceItemValue("SFLabels", sflabels);
			}

			// Now bring Folder references and our recording of them into
			// the SmartFile fields - this
			@SuppressWarnings("rawtypes")
			Vector refs = doc.getFolderReferences();
			Vector<String> unidrefs = new Vector<String>(refs.size());

			for (int i = 0; i < refs.size(); i++) {
				// We don't use empty references
				if (!"".equals(refs.get(i))) {
					// Add the UNID of the folder design to the field
					// since the native field $FolderRefs stores the UNID
					// of the Folder design element, so we need the UNID to
					// be able to compare them in a view to make things faster
					unidrefs.add(this.folderList.get((String) refs.get(i)));
				}
			}

			doc.replaceItemValue(Configuration.SMARTFILE_ITEMNAME, refs);
			doc.replaceItemValue(Configuration.SMARTFILE_REFNAME, unidrefs);
			doc.save();

		} catch (NotesException e) {
			Utils.logError(e);
		} catch (Exception e) {
			Utils.logError(e);
		}
	}

	private void rebuildModel(Database db, IProgressMonitor monitor,
			boolean saveModelAfterRebuild) {
		this.folderList = new HashMap<String, String>();
		this.folderRef = new HashMap<String, String>();
		List<View> views = this.getFoldersFromDB(db, this.folderList,
				this.folderRef);

		// We progress per view
		monitor.beginTask("Rebuilding vector model from scratch",
				views.size() + 30);

		// Rebuild the vector model
		Utils.logInfo("\tRebuilding vector model from scratch");

		// Count all the words in all the folders (tf) 20 items for moving docs
		this.wordCounts = this.countWordsInDatabase(views, db, monitor);

		// 30 items for refeshing words
		this.refeshWords(monitor);

		if (saveModelAfterRebuild) {
			this.save();
		}

		for (View v : views) {
			Utils.shred(v);
		}

	}

	private void refeshWords(IProgressMonitor monitor) {
		// Calculate the idf (inverse document frequency) for each word
		this.wordIDFs = this.calculateIDFs(wordCounts, monitor);
		monitor.worked(10);
		// Calculate ( tf * idf ) for each word in each folder
		this.wordTFIDFs = this.calculateTFIDFs(wordCounts, wordIDFs, monitor);
		monitor.worked(10);
		// Calculate vector length for each folder
		this.wordVectorLengths = this.calculateVectorLengths(wordTFIDFs,
				monitor);
		monitor.worked(10);
	}

	/*************************************************************************************************
	 * s e t S F L a b e l s Set the SFLables fields in all the documents in the
	 * specified views
	 **************************************************************************************************/
	private void setSFLabels(Database db, List<String> viewNames) {
		View v = null;
		Document doc = null;
		Document nextDoc = null;

		for (String viewName : viewNames) {

			try {

				v = db.getView(viewName);
				doc = v.getFirstDocument();

				while (doc != null) {
					nextDoc = v.getNextDocument(doc);

					if (!doc.hasItem(Configuration.SFLABELS_FIELD)
							|| doc.getItemValueString(
									Configuration.SFLABELS_FIELD).equals("")) {
						this.processDocument(doc, this.wordIDFs,
								this.wordTFIDFs, this.wordVectorLengths);
					}

					Utils.logInfo("\tSetting SwiftFile fields in " + viewName
							+ " document: \"" + doc.getUniversalID() + " - "
							+ doc.getItemValueString("Subject") + "\"");
					if (doc.getItemValueString(Configuration.SFLABELS_FIELD)
							.length() == 0) {
						Utils.logWarning("\t\tNo recommended folders.  Index is empty?  Rebuild your index?");
					} else {
						Utils.logInfo("\t\tRecommending folders:\t\t\t1: "
								+ doc.getItemValueString("SFLabel1")
								+ "\t\t\t2: "
								+ doc.getItemValueString("SFLabel2")
								+ "\t\t\t3: "
								+ doc.getItemValueString("SFLabel3"));
					}

					doc.recycle();
					doc = nextDoc;
				}

			} catch (NotesException e) {
				Utils.logError(
						"Unexpected exception during setSFLabels processing:"
								+ e.id + " " + e.text, e);

			} finally {
				Utils.shred(v, doc, nextDoc);
			}
		}

	}

}
