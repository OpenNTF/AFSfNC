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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

public class Configuration {

	/**
	 * Where is the file with the learning results
	 */
	private String smartfilePersistenceFile = null;

	/**
	 * The mail file (shortcut to ease runtime)
	 */
	private String mailFileName = null;

	/**
	 * Name of the debug file
	 */
	private String debugLogFile = null;

	/**
	 * Should the smartFile get persisted in the mail file --> Lots of stuff to
	 * save might not be a good idea
	 */
	private boolean persistInMailFile = false;

	/**
	 * What Folders to exclude
	 */
	private List<String> excludeList = null;

	/**
	 * Should hidden folders get ignored
	 */
	private boolean ignoreHiddenFolders = true;

	/**
	 * Fields to be processed with spaces removed like From, To, CopyTo
	 */
	private List<String> fieldsToProcessNoSpaces;

	/**
	 * Fields that should be processed as is Mostly body
	 */
	private List<String> fieldsToProcess;

	/**
	 * What is the default language of the scanner/stopwords
	 */
	private String defaultLanguage = "en";

	/**
	 * What languages do we support
	 */
	private List<String> languages;

	/**
	 * Words we don't care for - might be language dependent
	 */
	private HashMap<String, List<String>> stopWordList;

	/**
	 * Configuration object
	 */
	public Configuration() {
		// TODO: Move this to the configuration file
		this.fieldsToProcessNoSpaces = new LinkedList<String>(Arrays.asList(
				"From", "CopyTo", "BlindCopyTo"));
		this.fieldsToProcess = new LinkedList<String>(Arrays.asList("Subject",
				"Body"));
	}

	public String getDebugLogFile() {
		return debugLogFile;
	}

	private String getDefaultPersistenFile() {
		String osName = System.getProperty("os.name");
		if (osName.equals("Mac OS X")) {
			return new String(System.getProperty("user.home")
					+ "/Library/Application Support/SmartFile/SmartFile.db");
		}

		return new String(System.getProperty("user.home")
				+ System.getProperty("file.separator") + ".smartfile.db");
	}

	public List<String> getExcludeList() {
		return excludeList;
	}

	public List<String> getFieldsToProcess() {
		return fieldsToProcess;
	}

	public List<String> getFieldsToProcessNoSpaces() {
		return fieldsToProcessNoSpaces;
	}

	public List<String> getLanguages() {
		return languages;
	}

	public String getMailFileName() {
		return mailFileName;
	}

	public String getSmartfilePersistenceFile() {
		return smartfilePersistenceFile;
	}

	public List<String> getStopWordList(String language) {
		return stopWordList.get(language);
	}

	/**
	 * e x c l u d e d F o l d e r A routine to provide a common utility for
	 * excluding specific folders that we don't want to include in the model
	 * 
	 * @param folderName
	 * @return
	 */
	public boolean isExcludedFolder(String folderName) {
		if ((folderName.equals("")) || (folderName == null)) {
			return true;
		}
		if (this.isIgnoreHiddenFolders() && folderName.indexOf('(') == 0) {
			return true;
		}

		return this.getExcludeList().contains(folderName);

	}

	public boolean isIgnoreHiddenFolders() {
		return ignoreHiddenFolders;
	}

	public boolean isPersistInMailFile() {
		return persistInMailFile;
	}

	/**
	 * 
	 * @return is the given word a stopword in the default language
	 */
	public boolean isStopWord(String theWord) {
		return this.isStopWord(theWord, this.defaultLanguage);
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
			language = this.defaultLanguage;
		}
		if (!this.stopWordList.containsKey(language)) {
			return false;
		}
		return this.stopWordList.get(language).contains(theWord);
	}

	public String getDefaultLanguage() {
		return defaultLanguage;
	}

	/**
	 * Load the properties
	 * 
	 * @param in
	 *            a File or stream object
	 */
	public void load(InputStream in) throws IOException {

		Properties properties = new Properties();
		properties.load(in);

		// Get all properties
		this.debugLogFile = properties.getProperty("logfile");
		this.smartfilePersistenceFile = properties.getProperty("modelfile");
		this.mailFileName = properties.getProperty("mailfile");
		this.defaultLanguage = properties.getProperty("defaultLanguage", "en");
		String excludeListString = properties.getProperty("excludedFolders");
		String languageString = properties.getProperty("languages", "en;de");
		this.persistInMailFile = properties.getProperty("persistInMailFile",
				"false").equals("true");
		this.ignoreHiddenFolders = properties.getProperty(
				"ignoreHiddenFolders", "true").equals("true");

		// Exception handling
		if (this.smartfilePersistenceFile == null) {
			this.smartfilePersistenceFile = this.getDefaultPersistenFile();
		}

		if (mailFileName == null) {
			throw new IOException(
					"Please specify the full path to your Notes mail file in the configuration");
		}

		File f = new File(mailFileName);
		if (!f.exists()) {
			throw new IOException(
					"Unable to open the mail file you specified in "
							+ "\nPlease specify the full path to your Notes mail file.");
		}

		// Convert exclusion list
		this.excludeList = new ArrayList<String>();
		if (excludeListString != null) {
			this.excludeList
					.addAll(Arrays.asList(excludeListString.split(";")));
		}
		// Convert language list
		this.languages = new ArrayList<String>();
		this.languages.addAll(Arrays.asList(languageString.split(";")));

		this.loadStopWordList();
	}

	private void loadStopWordList() {
		if (this.stopWordList == null) {
			this.stopWordList = new HashMap<String, List<String>>();
		}

		// Read one stopword list per language
		for (String curLanguage : this.languages) {
			InputStream in = this.getClass().getResourceAsStream(
					"stopwords_en." + curLanguage);
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
	 * Persist the configuration
	 * 
	 * @param out
	 * @throws IOException
	 */
	public void save(OutputStream out) throws IOException {
		Properties properties = new Properties();

		properties.setProperty("logfile", this.debugLogFile);
		properties.setProperty("modelfile", this.smartfilePersistenceFile);
		properties.setProperty("mailfile", this.mailFileName);
		// TODO: is that the right format?
		properties.setProperty("excludedFolders", this.excludeList.toString());
		properties.setProperty("persistInMailFile",
				(this.persistInMailFile ? "true" : "false"));
		properties.setProperty("ignoreHiddenFolders",
				(this.ignoreHiddenFolders ? "true" : "false"));

		properties.store(out, "SmartFile Configuration");
	}

	public void setDebugLogFile(String debugLogFile) {
		this.debugLogFile = debugLogFile;
	}

	public void setExcludeList(List<String> excludeList) {
		this.excludeList = excludeList;
	}

	public void setIgnoreHiddenFolders(boolean ignoreHiddenFolders) {
		this.ignoreHiddenFolders = ignoreHiddenFolders;
	}

	public void setLanguages(List<String> languages) {
		this.languages = languages;
	}

	public void setMailFileName(String mailFileName) {
		this.mailFileName = mailFileName;
	}

	public void setPersistInMailFile(boolean persistInMailFile) {
		this.persistInMailFile = persistInMailFile;
	}

	public void setSmartfilePersistenceFile(String smartfilePersistenceFile) {
		this.smartfilePersistenceFile = smartfilePersistenceFile;
	}

	public void setStopWordList(String language, List<String> stopWordList) {
		this.stopWordList.put(language, stopWordList);
	}

}
