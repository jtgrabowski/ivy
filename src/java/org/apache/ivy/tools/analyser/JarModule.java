/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.tools.analyser;

import java.io.File;

import org.apache.ivy.ModuleRevisionId;


public class JarModule {
	private ModuleRevisionId _mrid;
	private File _jar;
	
	public JarModule(ModuleRevisionId mrid, File jar) {
		_mrid = mrid;
		_jar = jar;
	}

	public File getJar() {
		return _jar;
	}

	public ModuleRevisionId getMrid() {
		return _mrid;
	}
	
	public String toString() {
		return _jar + " " + _mrid;
	}
	
}