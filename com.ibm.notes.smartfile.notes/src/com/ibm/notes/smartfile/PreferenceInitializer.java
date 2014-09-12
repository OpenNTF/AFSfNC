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

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

	public PreferenceInitializer() {
		// No action required
	}

	@Override
	public void initializeDefaultPreferences() {

		IPreferenceStore store = Activator.getDefault().getPreferenceStore();

		// Where do we save the model file
		IPath savewhere = Activator.getDefault().getStateLocation();
		store.setDefault(Configuration.PROPERTY_PERSISTENCE_DIRECTORY,
				savewhere.addTrailingSeparator().toPortableString());

		// Should we store in mail file (bad idea since it is really big
		store.setDefault(Configuration.PROPERTY_PERSIST_IN_MAILFILE, false);

		// the default language
		store.setDefault(Configuration.PROPERTY_DEFAULTLANGUAGE, "en");

		// What fields to process normal and taking spaces out
		store.setDefault(Configuration.PROPERTY_FIELDS_PROCESS_NOSPACES,
				"From,CopyTo,BlindCopyTo");

		store.setDefault(Configuration.PROPERTY_FIELDS_PROCESS, "Body,Subject");

		// Exclude folders that are not visible (that begin with "(" )
		store.setDefault(Configuration.PROPERTY_EXCLUDE_HIDDENFOLDERS, true);

		// Name list of folders to exclude
		store.setDefault(Configuration.PROPERTY_FOLDERS_TO_EXCLUDE, "");

		// By default the plug-in is not enabled to run
		store.setDefault(Configuration.PROPERTY_ISENDABLED, false);

	}

}
