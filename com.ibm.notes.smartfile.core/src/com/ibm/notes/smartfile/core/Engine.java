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
package com.ibm.notes.smartfile.core;

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
import lotus.domino.View;

/**
 * The SmartFile Engine that learns the words and folder distribution
 * 
 * @author stw
 * 
 */
public class Engine {

	// The number of folders in the database
	private ArrayList<String> folderList = new ArrayList<String>();

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
	 * The settings from the preferences
	 */
	private Configuration config = null;

	/**
	 * Words we don't care for
	 */

	/**
	 * The Engine can only be initialized when we have a configuration
	 * 
	 * @param config
	 */
	public Engine(Configuration config) {
		this.config = config;
	}

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
			HashMap<String, HashMap<String, Double>> wordCounts) {
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
			HashMap<String, Double> wordIDFs) {
		HashMap<String, HashMap<String, Double>> tfidfmap = new HashMap<String, HashMap<String, Double>>();

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
		return tfidfmap;
	}

	/*************************************************************************************************
	 * c a l c u l a t e V e c t o r L e n g t h s
	 **************************************************************************************************/

	// Calculate the vector length for each folder
	private HashMap<String, Double> calculateVectorLengths(
			HashMap<String, HashMap<String, Double>> wordTFIDFs) {

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

		return newmap;
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
	public HashMap<String, HashMap<String, Double>> countWords(List<View> views) {

		HashMap<String, HashMap<String, Double>> totalCounts = new HashMap<String, HashMap<String, Double>>();

		Document doc = null;
		Document nextDoc = null;

		for (View v : views) {
			try {

				HashMap<String, Double> folderCount = new HashMap<String, Double>();

				// We need the docs, so we can skip the viewentrycollection
				doc = v.getFirstDocument();

				while (doc != null) {
					nextDoc = v.getNextDocument(doc);

					HashMap<String, Double> docCount = this
							.extractWordsFromDocument(doc);
					this.addHashMapValues(folderCount, docCount);

					doc.recycle();
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
				e.printStackTrace();
				Utils.debugLog(e.id + " " + e.text, e);
			}

		}

		return totalCounts;
	}

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

	public ArrayList<String> getFolderList() {
		return folderList;
	}

	public List<View> getFoldersFromDB(Database db, List<String> folderNames) {
		// Provides only Folders that are not excluded
		List<View> result = null;

		try {
			@SuppressWarnings("rawtypes")
			Vector allViews = db.getViews();
			result = new ArrayList<View>(allViews.size());

			for (Object x : allViews) {
				View v = (View) x;
				if (this.config.isExcludedFolder(v.getName())) {
					Utils.shred(v);
				} else {
					result.add(v);
					if (folderNames != null) {
						folderNames.add(v.getName());
					}
				}
			}

		} catch (NotesException e) {
			e.printStackTrace();
		}
		return result;
	}

	private String getLabelWithOffset(int base) {
		return "SFLabel" + String.valueOf(base + 1);
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

	/*************************************************************************************************
	 * l e a r n
	 ************************************************************************************************** 
	 * 
	 * Processed one document and adds to the Engine's knowledge
	 * 
	 */

	public boolean learn(Document doc) {

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
			e.printStackTrace();
			Utils.debugLog(e.id + " " + e.text, e);
		} catch (Exception e) {
			e.printStackTrace();
			Utils.debugLog("Java Exception in learn:", e);
		}

		return learned;

	}

	@SuppressWarnings("unchecked")
	public void load(InputStream in) throws IOException, ClassNotFoundException {
		// Read the vector model, and other values, from a file
		ObjectInputStream ois = new ObjectInputStream(in);
		this.folderList = (ArrayList<String>) ois.readObject();
		this.wordCounts = (HashMap<String, HashMap<String, Double>>) ois
				.readObject();
		this.wordIDFs = (HashMap<String, Double>) ois.readObject();
		this.wordTFIDFs = (HashMap<String, HashMap<String, Double>>) ois
				.readObject();
		this.wordVectorLengths = (HashMap<String, Double>) ois.readObject();
		ois.close();

	}

	/*************************************************************************************************
	 * p r o c e s s D o c u m e n t
	 **************************************************************************************************/

	// Analyze a document and set the three SwiftFile fields to the folders that
	// most closely match
	public void processDocument(Document doc, HashMap<String, Double> wordIDFs,
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

			@SuppressWarnings("rawtypes")
			Vector refs = doc.getFolderReferences();
			doc.replaceItemValue("SmartFile", refs);
			doc.save();

		} catch (NotesException e) {
			System.out.println("NotesException: " + e.id + " " + e.text);
			e.printStackTrace();
			Utils.debugLog(e.id + " " + e.text, e);
		} catch (Exception e) {
			e.printStackTrace();
			Utils.debugLog("Java Exception in processDocument:", e);
		}
	}

	public void rebuildModel(Database db) {
		this.folderList = new ArrayList<String>();
		List<View> views = this.getFoldersFromDB(db, this.folderList);

		// Rebuild the vector model
		Utils.debugLog("\tRebuilding vector model from scratch");

		// Count all the words in all the folders (tf)
		this.wordCounts = this.countWords(views);

		this.refeshWords();

		for (View v : views) {
			Utils.shred(v);
		}

	}

	public void refeshWords() {
		// Calculate the idf (inverse document frequency) for each word
		this.wordIDFs = this.calculateIDFs(wordCounts);
		// Calculate ( tf * idf ) for each word in each folder
		this.wordTFIDFs = this.calculateTFIDFs(wordCounts, wordIDFs);
		// Calculate vector length for each folder
		this.wordVectorLengths = this.calculateVectorLengths(wordTFIDFs);
	}

	public void save(OutputStream out) throws IOException {

		// Persist the vector model, and other values into wherever that stream
		// goes
		ObjectOutputStream oos = new ObjectOutputStream(out);
		oos.writeObject(folderList);
		oos.writeObject(wordCounts);
		oos.writeObject(wordIDFs);
		oos.writeObject(wordTFIDFs);
		oos.writeObject(wordVectorLengths);
		oos.close();

	}

	/*************************************************************************************************
	 * s e t S F L a b e l s
	 **************************************************************************************************/

	// Set the SFLables fields in all the documents in the specified view
	// collection
	public void setSFLabels(Database db, String viewName) {
		View v = null;
		Document doc = null;
		Document nextDoc = null;
		try {

			v = db.getView(viewName);
			doc = v.getFirstDocument();

			while (doc != null) {
				nextDoc = v.getNextDocument(doc);

				if (!doc.hasItem("SFLabels")
						|| doc.getItemValueString("SFLabels").equals("")) {
					this.processDocument(doc, this.wordIDFs, this.wordTFIDFs,
							this.wordVectorLengths);
				}
				Utils.debugLog("\tSetting SwiftFile fields in " + viewName
						+ " document: \"" + doc.getUniversalID() + " - "
						+ doc.getItemValueString("Subject") + "\"");
				if (doc.getItemValueString("SFLabels").length() == 0) {
					Utils.debugLog("\t\tNo recommended folders.  Index is empty?  Rebuild your index?");
				} else {
					Utils.debugLog("\t\tRecommending folders:");
					Utils.debugLog("\t\t\t1: "
							+ doc.getItemValueString("SFLabel1"));
					Utils.debugLog("\t\t\t2: "
							+ doc.getItemValueString("SFLabel2"));
					Utils.debugLog("\t\t\t3: "
							+ doc.getItemValueString("SFLabel3"));
				}

				doc.recycle();
				doc = nextDoc;
			}

		} catch (NotesException e) {
			Utils.debugLog("Unexpected exception during setSFLabels processing");
			Utils.debugLog(e.id + " " + e.text, e);

		} finally {
			Utils.shred(v, doc, nextDoc);
		}
	}

}
