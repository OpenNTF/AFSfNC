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

import lotus.domino.NotesException;
import lotus.domino.Session;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.ui.IStartup;

import com.ibm.notes.java.api.util.NotesSessionJob;

public class NotesStartup implements IStartup {

	public NotesStartup() {
	}

	@Override
	public void earlyStartup() {

		Configuration config = Activator.getDefault().getConfig();

		// We check that we only run if it is enables
		if (!config.isEnabled()) {
			return;
		}

		// Start the initial setting
		new NotesSessionJob("SmartFile Startup") {

			/**
			 * Runs the processing of all folders and new messages at start of
			 * the Notes client
			 */
			@Override
			protected IStatus runInNotesThread(Session s,
					IProgressMonitor monitor) throws NotesException {
				Engine engine = Activator.getDefault().getEngine();
				return engine.scheduledProcessing(s, monitor);
			}

		}.schedule();
	}
}
