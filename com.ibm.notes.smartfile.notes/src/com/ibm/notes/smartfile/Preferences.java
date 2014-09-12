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

import java.util.Map;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class Preferences extends FieldEditorPreferencePage implements
		IWorkbenchPreferencePage {

	public Preferences() {
		super(GRID);

	}

	public void createFieldEditors() {

		Composite parent = this.getFieldEditorParent();

		this.addField(new BooleanFieldEditor(Configuration.PROPERTY_ISENDABLED,
				"SmartFile is &enabled:", parent));

		FileFieldEditor mf = new FileFieldEditor(
				Configuration.PROPERTY_MAILFILENAME, "&Your mail file name:",
				parent);
		String[] nsfExtensions = { "*.nsf", "*.*" };
		mf.setFileExtensions(nsfExtensions);
		this.addField(mf);

		DirectoryFieldEditor modf = new DirectoryFieldEditor(
				Configuration.PROPERTY_PERSISTENCE_DIRECTORY,
				"&Directory to store model\n(leave default if in doubt):",
				parent);
		modf.setEmptyStringAllowed(true);
		modf.setValidateStrategy(FileFieldEditor.VALIDATE_ON_FOCUS_LOST);
		this.addField(modf);

		BooleanFieldEditor b = new BooleanFieldEditor(
				Configuration.PROPERTY_PERSIST_IN_MAILFILE,
				"&Save to mail file\n(Recommended to leave it off)", parent);
		b.setEnabled(false, parent);
		this.addField(b);

		this.addField(new StringFieldEditor(
				Configuration.PROPERTY_FIELDS_PROCESS_NOSPACES,
				"Fields to process 1/2 (People)\n(content spaces removed) - Comma separated:",
				parent));

		this.addField(new StringFieldEditor(
				Configuration.PROPERTY_FIELDS_PROCESS,
				"Fields to process 2/2\n(Subject, Body) - Comma separated:",
				getFieldEditorParent()));

		this.addField(new BooleanFieldEditor(
				Configuration.PROPERTY_EXCLUDE_HIDDENFOLDERS,
				"Exclude hidden Folders\n (recommended: YES)", parent));

		this.addField(new StringFieldEditor(
				Configuration.PROPERTY_FOLDERS_TO_EXCLUDE,
				"Folders to ignore\n (Comma separated):", parent));

		String langArray[][] = this.mapToDoubleArray(Activator.getDefault()
				.getConfig().getLanguages());

		this.addField(new ComboFieldEditor(
				Configuration.PROPERTY_DEFAULTLANGUAGE, "Default language",
				langArray, parent));

	}

	/**
	 * Turn the flat list of languages into
	 * 
	 * @param languages
	 *            Language name/value pairs
	 * @return Array with 2 dimensions
	 */
	private String[][] mapToDoubleArray(Map<String, String> languages) {
		String[][] result = new String[languages.size()][2];
		int i = 0;
		for (Map.Entry<String, String> e : languages.entrySet()) {
			result[i][0] = e.getValue();
			result[i][1] = e.getKey();
			i++;
		}

		return result;
	}

	@Override
	public void init(IWorkbench workbench) {
		this.setPreferenceStore(Activator.getDefault().getPreferenceStore());
		this.setDescription("SmartFile for Lotus Notes execution preferences:");
	}

}
