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

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import lotus.domino.NotesException;
import lotus.domino.Session;

import org.eclipse.jface.preference.IPreferenceStore;

public class Configuration {

	/**
	 * Constants used in all of the application
	 */
	public static final String PERSISTENCE_FILE_NAME = "smartfile.dat";
	public static final String SMARTFILE_VIEW = "($SmartFileToProcess)";
	public static final String SMARTFILE_ITEMNAME = "SmartFile";
	public static final String SMARTFILE_REFNAME = "SmartFileRef";
	public static final String FOLDER_REF = "$FolderRef";

	public static final String SFLABELS_FIELD = "SFLabels";

	public static final String PROPERTY_PERSISTENCE_DIRECTORY = "modeldirectory";
	public static final String PROPERTY_PERSIST_IN_MAILFILE = "persistinmailfile";
	public static final String PROPERTY_DEFAULTLANGUAGE = "defaultlanguage";
	public static final String PROPERTY_FIELDS_PROCESS_NOSPACES = "fieldsToProcessNoSpaces";
	public static final String PROPERTY_FIELDS_PROCESS = "fieldsToProcess";
	public static final String PROPERTY_FOLDERS_TO_EXCLUDE = "foldersToExclude";
	public static final String PROPERTY_EXCLUDE_HIDDENFOLDERS = "excludehiddenfolders";
	public static final String PROPERTY_MAILFILENAME = "mailfilename";
	public static final String PROPERTY_ISENDABLED = "isenabled";

	/**
	 * Link to the preference store
	 */
	private IPreferenceStore store;

	/**
	 * What languages do we support
	 */
	private Map<String, String> languages;

	/**
	 * Words we don't care for - might be language dependent
	 */
	private HashMap<String, List<String>> stopWordList;

	/**
	 * Configuration object
	 */
	public Configuration() {
		// Where all the preferences come from
		this.store = Activator.getDefault().getPreferenceStore();

		// Since the language stopword list is inside the JAR
		// we hardcode it here
		this.languages = new HashMap<String, String>();
		this.languages.put("en", "English");
		this.languages.put("de", "Deutsch");

		// Load the stopwords
		this.loadStopWordList();

	}

	public String getDefaultLanguage() {
		return this.store.getString(Configuration.PROPERTY_DEFAULTLANGUAGE);
	}

	public List<String> getExcludeList() {
		List<String> excludeList = new ArrayList<String>();
		String[] folders = this.store.getString(
				Configuration.PROPERTY_FOLDERS_TO_EXCLUDE).split(",");

		for (String f : folders) {
			if (f != null && f.trim() != "") {
				excludeList.add(f.trim());
			}
		}

		return excludeList;
	}

	public List<String> getFieldsToProcess() {
		List<String> fieldsToProcess = new ArrayList<String>();
		String[] fields = this.store.getString(
				Configuration.PROPERTY_FIELDS_PROCESS).split(",");
		for (String f : fields) {
			if (f != null && f.trim() != "") {
				fieldsToProcess.add(f.trim());
			}
		}

		return fieldsToProcess;
	}

	public List<String> getFieldsToProcessNoSpaces() {
		List<String> fieldsToProcessNoSpaces = new ArrayList<String>();
		String[] fields = store.getString(
				Configuration.PROPERTY_FIELDS_PROCESS_NOSPACES).split(",");
		for (String f : fields) {
			if (f != null && f.trim() != "") {
				fieldsToProcessNoSpaces.add(f.trim());
			}
		}

		return fieldsToProcessNoSpaces;
	}

	public Map<String, String> getLanguages() {
		return this.languages;
	}

	public String getMailFileName(Session s) {
		// When we pass in a session we want up update the mail file
		String newMailFile = null;
		if (s != null) {
			try {
				newMailFile = this.updateCurrentMailFileName(s);
			} catch (NotesException e) {
				Utils.logError(e);
			}
			if (newMailFile != null) {
				this.store.setValue(Configuration.PROPERTY_MAILFILENAME,
						newMailFile);
			}
		}
		return this.store.getString(Configuration.PROPERTY_MAILFILENAME);
	}

	public String getSmartfilePersistenceFile() {
		// TODO: check if we need a separator
		return this.store
				.getString(Configuration.PROPERTY_PERSISTENCE_DIRECTORY)
				+ Configuration.PERSISTENCE_FILE_NAME;
	}

	public List<String> getStopWordList(String language) {
		return stopWordList.get(language);
	}

	/**
	 * 
	 * @return true if SmartFile is supposed to run
	 */
	public boolean isEnabled() {
		return this.store.getBoolean(Configuration.PROPERTY_ISENDABLED);
	}

	/**
	 * e x c l u d e d F o l d e r A routine to provide a common utility for
	 * excluding specific folders that we don't want to include in the model
	 * 
	 * @param folderName
	 * @return
	 */
	public boolean isExcludedFolder(String folderName) {
		if (((folderName == null) || folderName.equals(""))) {
			return true;
		}

		if (this.isIgnoreHiddenFolders() && folderName.indexOf('(') == 0) {
			return true;
		}

		return this.getExcludeList().contains(folderName);

	}

	public boolean isIgnoreHiddenFolders() {
		return this.store
				.getBoolean(Configuration.PROPERTY_EXCLUDE_HIDDENFOLDERS);
	}

	public boolean isPersistInMailFile() {
		return this.store
				.getBoolean(Configuration.PROPERTY_PERSIST_IN_MAILFILE);
	}

	/**
	 * 
	 * @return is the given word a stopword in the default language
	 */
	public boolean isStopWord(String theWord) {
		return this.isStopWord(theWord, this.getDefaultLanguage());
	}

	/**
	 * Returns if a word is in the stopword list in the given language
	 * 
	 * @param theWord
	 * @param language
	 * @return
	 */
	public boolean isStopWord(String theWord, String language) {
		if (language == null) {
			language = this.getDefaultLanguage();
		}
		if (!this.stopWordList.containsKey(language)) {
			return false;
		}
		return this.stopWordList.get(language).contains(theWord);
	}

	private void loadStopWordList() {
		if (this.stopWordList == null) {
			this.stopWordList = new HashMap<String, List<String>>();
		}

		// Read one stopword list per language
		for (Map.Entry<String, String> langEntry : this.languages.entrySet()) {
			String curLanguage = langEntry.getKey();
			InputStream in = this.getClass().getResourceAsStream(
					"stopwords_" + curLanguage + ".txt");
			if (in != null) {
				Scanner s = new Scanner(in);
				ArrayList<String> newStopWords = new ArrayList<String>();
				while (s.hasNextLine()) {
					String curLine = s.nextLine().trim();
					// Comment lines begin with #
					if (!curLine.startsWith("#")) {
						// Inline comments begin with # after the word
						if (curLine.indexOf("#") > 0) {
							curLine = curLine.substring(0,
									(curLine.indexOf("#") - 1)).trim();
						}
						// make sure we don't have empty lines as stopwords
						if (!curLine.equals("")) {
							newStopWords.add(curLine);
						}
					}
				}
				this.stopWordList.put(curLanguage, newStopWords);
			}
		}
	}

	/**
	 * Returns the current mailfile name
	 * 
	 * @param s
	 *            the session
	 * @return the full path to a local mail file or NULL
	 * @throws NotesException
	 */
	private String updateCurrentMailFileName(Session s) throws NotesException {
		// True gets the name without $ at front
		String mailtype = s.getEnvironmentString("MailType", true); // 0=server
																	// 1=local

		if ("0".equals(mailtype)) {
			// If the mail file is on the server, we can't retrieve it!
			// As a precaution we switch SwiftFile off
			this.store.setValue(Configuration.PROPERTY_ISENDABLED, false);
			// TODO: log an error!
			return null;
		}

		String directory = s.getEnvironmentString("Directory", true);
		String mailfile = s.getEnvironmentString("MailFile", true);
		String fullName = directory + File.separator + mailfile;
		// We got here, we are OK
		return fullName;
	}
}
