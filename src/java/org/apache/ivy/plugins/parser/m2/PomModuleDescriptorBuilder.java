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
package org.apache.ivy.plugins.parser.m2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.cache.ArtifactOrigin;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.Configuration;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DefaultDependencyArtifactDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultExcludeRule;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.License;
import org.apache.ivy.core.module.descriptor.MDArtifact;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.descriptor.OverrideDependencyDescriptorMediator;
import org.apache.ivy.core.module.descriptor.Configuration.Visibility;
import org.apache.ivy.core.module.id.ArtifactId;
import org.apache.ivy.core.module.id.ModuleId;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.plugins.matcher.ExactPatternMatcher;
import org.apache.ivy.plugins.matcher.PatternMatcher;
import org.apache.ivy.plugins.parser.ModuleDescriptorParser;
import org.apache.ivy.plugins.parser.ParserSettings;
import org.apache.ivy.plugins.parser.m2.PomReader.PomDependencyData;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.apache.ivy.util.Message;


/**
 * Build a module descriptor.  This class handle the complexity of the structure of an ivy 
 * ModuleDescriptor and isolate the PomModuleDescriptorParser from it. 
 */
public class PomModuleDescriptorBuilder {

    
    private static final int DEPENDENCY_MANAGEMENT_KEY_PARTS_COUNT = 4;

    public static final Configuration[] MAVEN2_CONFIGURATIONS = new Configuration[] {
        new Configuration("default", Visibility.PUBLIC,
                "runtime dependencies and master artifact can be used with this conf",
                new String[] {"runtime", "master"}, true, null),
        new Configuration("master", Visibility.PUBLIC,
                "contains only the artifact published by this module itself, "
                + "with no transitive dependencies",
                new String[0], true, null),
        new Configuration("compile", Visibility.PUBLIC,
                "this is the default scope, used if none is specified. "
                + "Compile dependencies are available in all classpaths.",
                new String[0], true, null),
        new Configuration("provided", Visibility.PUBLIC,
                "this is much like compile, but indicates you expect the JDK or a container "
                + "to provide it. "
                + "It is only available on the compilation classpath, and is not transitive.",
                new String[0], true, null),
        new Configuration("runtime", Visibility.PUBLIC,
                "this scope indicates that the dependency is not required for compilation, "
                + "but is for execution. It is in the runtime and test classpaths, "
                + "but not the compile classpath.",
                new String[] {"compile"}, true, null),
        new Configuration("test", Visibility.PRIVATE,
                "this scope indicates that the dependency is not required for normal use of "
                + "the application, and is only available for the test compilation and "
                + "execution phases.",
                new String[] {"runtime"}, true, null),
        new Configuration("system", Visibility.PUBLIC,
                "this scope is similar to provided except that you have to provide the JAR "
                + "which contains it explicitly. The artifact is always available and is not "
                + "looked up in a repository.",
                new String[0], true, null),
        new Configuration("sources", Visibility.PUBLIC,
            "this configuration contains the source artifact of this module, if any.",
            new String[0], true, null),
        new Configuration("javadoc", Visibility.PUBLIC,
            "this configuration contains the javadoc artifact of this module, if any.",
            new String[0], true, null),
        new Configuration("optional", Visibility.PUBLIC, 
                "contains all optional dependencies", new String[0], true, null)
                };

    static final Map MAVEN2_CONF_MAPPING = new HashMap();

    private static final String DEPENDENCY_MANAGEMENT = "m:dependency.management";        
    private static final String PROPERTIES = "m:properties";
    private static final String EXTRA_INFO_DELIMITER = "__";
    private static final Collection/*<String>*/ JAR_PACKAGINGS = Arrays.asList(
                new String[] {"ejb", "bundle", "maven-plugin", "eclipse-plugin",
                        "jbi-component", "jbi-shared-library", "orbit", "hk2-jar"});

    
    static interface ConfMapper {
        public void addMappingConfs(DefaultDependencyDescriptor dd, boolean isOptional);
    }
    
    static {
        MAVEN2_CONF_MAPPING.put("compile", new ConfMapper() {
            public void addMappingConfs(DefaultDependencyDescriptor dd, boolean isOptional) {
                if (isOptional) {
                    dd.addDependencyConfiguration("optional", "compile(*)");
                    //dd.addDependencyConfiguration("optional", "provided(*)");
                    dd.addDependencyConfiguration("optional", "master(*)");
                    
                } else {
                    dd.addDependencyConfiguration("compile", "compile(*)");
                    //dd.addDependencyConfiguration("compile", "provided(*)");
                    dd.addDependencyConfiguration("compile", "master(*)");
                    dd.addDependencyConfiguration("runtime", "runtime(*)");
                }
            }
        });
        MAVEN2_CONF_MAPPING.put("provided", new ConfMapper() {
            public void addMappingConfs(DefaultDependencyDescriptor dd, boolean isOptional) {
                if (isOptional) {
                    dd.addDependencyConfiguration("optional", "compile(*)");
                    dd.addDependencyConfiguration("optional", "provided(*)");
                    dd.addDependencyConfiguration("optional", "runtime(*)");
                    dd.addDependencyConfiguration("optional", "master(*)");                    
                } else {
                    dd.addDependencyConfiguration("provided", "compile(*)");
                    dd.addDependencyConfiguration("provided", "provided(*)");
                    dd.addDependencyConfiguration("provided", "runtime(*)");
                    dd.addDependencyConfiguration("provided", "master(*)");
                }
            }
        });
        MAVEN2_CONF_MAPPING.put("runtime", new ConfMapper() {
            public void addMappingConfs(DefaultDependencyDescriptor dd, boolean isOptional) {
                if (isOptional) {
                    dd.addDependencyConfiguration("optional", "compile(*)");
                    dd.addDependencyConfiguration("optional", "provided(*)");
                    dd.addDependencyConfiguration("optional", "master(*)");
                    
                } else {
                    dd.addDependencyConfiguration("runtime", "compile(*)");
                    dd.addDependencyConfiguration("runtime", "runtime(*)");
                    dd.addDependencyConfiguration("runtime", "master(*)");
                }
            }
        });
        MAVEN2_CONF_MAPPING.put("test", new ConfMapper() {
            public void addMappingConfs(DefaultDependencyDescriptor dd, boolean isOptional) {
                //optional doesn't make sense in the test scope
                dd.addDependencyConfiguration("test", "runtime(*)");
                dd.addDependencyConfiguration("test", "master(*)");
            }
        });
        MAVEN2_CONF_MAPPING.put("system", new ConfMapper() {
            public void addMappingConfs(DefaultDependencyDescriptor dd, boolean isOptional) {
                //optional doesn't make sense in the system scope
                dd.addDependencyConfiguration("system", "master(*)");
            }
        });
    }

    
    
    private final PomModuleDescriptor ivyModuleDescriptor;


    private ModuleRevisionId mrid;

    private DefaultArtifact mainArtifact;
    
    private ParserSettings parserSettings;

    private static final String WRONG_NUMBER_OF_PARTS_MSG = "what seemed to be a dependency "
            + "management extra info exclusion had the wrong number of parts (should have 2) ";

    
    public PomModuleDescriptorBuilder(
            ModuleDescriptorParser parser, Resource res, ParserSettings ivySettings) {
        ivyModuleDescriptor = new PomModuleDescriptor(parser, res);
        ivyModuleDescriptor.setResolvedPublicationDate(new Date(res.getLastModified()));
        for (int i = 0; i < MAVEN2_CONFIGURATIONS.length; i++) {
            ivyModuleDescriptor.addConfiguration(MAVEN2_CONFIGURATIONS[i]);
        }
        ivyModuleDescriptor.setMappingOverride(true);
        ivyModuleDescriptor.addExtraAttributeNamespace("m", Ivy.getIvyHomeURL() + "maven");
        parserSettings = ivySettings;
    }


    public ModuleDescriptor getModuleDescriptor() {
        return ivyModuleDescriptor;
    }


    public void setModuleRevId(String groupId, String artifactId, String version) {
        mrid = ModuleRevisionId.newInstance(groupId, artifactId, version);
        ivyModuleDescriptor.setModuleRevisionId(mrid);
        
        if ((version == null) || version.endsWith("SNAPSHOT")) {
            ivyModuleDescriptor.setStatus("integration");
        } else {
            ivyModuleDescriptor.setStatus("release");
        }
     }
    
    public void setHomePage(String homePage) {
        ivyModuleDescriptor.setHomePage(homePage);
    }

    public void setDescription(String description) {
        ivyModuleDescriptor.setDescription(description);
    }

    public void setLicenses(License[] licenses) {
        for (int i = 0; i < licenses.length; i++) {
            ivyModuleDescriptor.addLicense(licenses[i]);
        }
    }

    public void addMainArtifact(String artifactId, String packaging) {
        String ext;
        
        /*
         * TODO: we should make packaging to ext mapping configurable, since it's not possible to
         * cover all cases.
         */
        if ("pom".equals(packaging)) {
            // no artifact defined! Add the default artifact if it exist.
            DependencyResolver resolver = parserSettings.getResolver(mrid);
            
            if (resolver != null) {
                DefaultArtifact artifact = new DefaultArtifact(
                                    mrid, new Date(), artifactId, "jar", "jar");
                ArtifactOrigin artifactOrigin = resolver.locate(artifact);
                
                if (!ArtifactOrigin.isUnknown(artifactOrigin)) {
                    mainArtifact = artifact;
                    ivyModuleDescriptor.addArtifact("master", mainArtifact);
                }
            }

            return;
        } else if (JAR_PACKAGINGS.contains(packaging)) {
            ext = "jar";
        } else if ("pear".equals(packaging)) {
            ext = "phar";
        } else {
            ext = packaging;
        }

        mainArtifact = new DefaultArtifact(mrid, new Date(), artifactId, packaging, ext);
        ivyModuleDescriptor.addArtifact("master", mainArtifact);
    }

    public void addDependency(Resource res, PomDependencyData dep) {
        String scope = dep.getScope();
        if ((scope != null) && (scope.length() > 0) && !MAVEN2_CONF_MAPPING.containsKey(scope)) {
            // unknown scope, defaulting to 'compile'
            scope = "compile";
        }
        
        String version = dep.getVersion();
        version = (version == null || version.length() == 0) ? getDefaultVersion(dep) : version;
        ModuleRevisionId moduleRevId = ModuleRevisionId.newInstance(dep.getGroupId(), dep
                .getArtifactId(), version);

        // Some POMs depend on theirselfves, don't add this dependency: Ivy doesn't allow this!
        // Example: http://repo2.maven.org/maven2/net/jini/jsk-platform/2.1/jsk-platform-2.1.pom
        ModuleRevisionId mRevId = ivyModuleDescriptor.getModuleRevisionId();
        if ((mRevId != null) && mRevId.getModuleId().equals(moduleRevId.getModuleId())) {
            return;
        }

        DefaultDependencyDescriptor dd = new PomDependencyDescriptor(dep, ivyModuleDescriptor, moduleRevId);
        scope = (scope == null || scope.length() == 0) ? getDefaultScope(dep) : scope;
        ConfMapper mapping = (ConfMapper) MAVEN2_CONF_MAPPING.get(scope);
        mapping.addMappingConfs(dd, dep.isOptional());
        Map extraAtt = new HashMap();
        if ((dep.getClassifier() != null) || ((dep.getType() != null) && !"jar".equals(dep.getType()))) {
            String type = "jar";
            if (dep.getType() != null) {
                type = dep.getType();
            }
            String ext = type;

            // if type is 'test-jar', the extension is 'jar' and the classifier is 'tests'
            // Cfr. http://maven.apache.org/guides/mini/guide-attached-tests.html
            if ("test-jar".equals(type)) {
                ext = "jar";
                extraAtt.put("m:classifier", "tests");
            } else if (JAR_PACKAGINGS.contains(type)) {
                ext = "jar";
            }            
            
            // we deal with classifiers by setting an extra attribute and forcing the
            // dependency to assume such an artifact is published
            if (dep.getClassifier() != null) {
                extraAtt.put("m:classifier", dep.getClassifier());
            }
            DefaultDependencyArtifactDescriptor depArtifact = 
                    new DefaultDependencyArtifactDescriptor(dd, dd.getDependencyId().getName(),
                        type, ext, null, extraAtt);
            // here we have to assume a type and ext for the artifact, so this is a limitation
            // compared to how m2 behave with classifiers
            String optionalizedScope = dep.isOptional() ? "optional" : scope;
            dd.addDependencyArtifact(optionalizedScope, depArtifact);
        }
        
        // experimentation shows the following, excluded modules are
        // inherited from parent POMs if either of the following is true:
        // the <exclusions> element is missing or the <exclusions> element
        // is present, but empty.
        List /*<ModuleId>*/ excluded = dep.getExcludedModules();
        if (excluded.isEmpty()) {
            excluded = getDependencyMgtExclusions(ivyModuleDescriptor, dep.getGroupId(), dep.getArtifactId());
        }
        for (Iterator itExcl = excluded.iterator(); itExcl.hasNext();) {
            ModuleId excludedModule = (ModuleId) itExcl.next();
            String[] confs = dd.getModuleConfigurations();
            for (int k = 0; k < confs.length; k++) {
                dd.addExcludeRule(confs[k], new DefaultExcludeRule(new ArtifactId(
                    excludedModule, PatternMatcher.ANY_EXPRESSION,
                                PatternMatcher.ANY_EXPRESSION,
                                PatternMatcher.ANY_EXPRESSION),
                                ExactPatternMatcher.INSTANCE, null));
            }
        }
    
        ivyModuleDescriptor.addDependency(dd);
    }

    public void addDependency(DependencyDescriptor descriptor) {
        // Some POMs depend on themselves through their parent pom, don't add this dependency
        // since Ivy doesn't allow this!
        // Example: http://repo2.maven.org/maven2/com/atomikos/atomikos-util/3.6.4/atomikos-util-3.6.4.pom
        ModuleId dependencyId = descriptor.getDependencyId();
        ModuleRevisionId mRevId = ivyModuleDescriptor.getModuleRevisionId();
        if ((mRevId != null) && mRevId.getModuleId().equals(dependencyId)) {
            return;
        }
        
        ivyModuleDescriptor.addDependency(descriptor);
    }


    public void addDependencyMgt(PomDependencyMgt dep) {
        ivyModuleDescriptor.addDependencyManagement(dep);

        String key = getDependencyMgtExtraInfoKeyForVersion(dep.getGroupId(), dep.getArtifactId());
        ivyModuleDescriptor.addExtraInfo(key, dep.getVersion());
        if (dep.getScope() != null) {
            String scopeKey = getDependencyMgtExtraInfoKeyForScope(
                                    dep.getGroupId(), dep.getArtifactId());
            ivyModuleDescriptor.addExtraInfo(scopeKey, dep.getScope());
        }
        if (!dep.getExcludedModules().isEmpty()) {
            final String exclusionPrefix = getDependencyMgtExtraInfoPrefixForExclusion(
                    dep.getGroupId(), dep.getArtifactId());
            int index = 0;
            for (final Iterator iter = dep.getExcludedModules().iterator(); iter.hasNext();) {
                final ModuleId excludedModule = (ModuleId) iter.next();
                ivyModuleDescriptor.addExtraInfo(exclusionPrefix + index,
                        excludedModule.getOrganisation() + EXTRA_INFO_DELIMITER + excludedModule.getName());
                index += 1;
            }
        }
        // dependency management info is also used for version mediation of transitive dependencies
        ivyModuleDescriptor.addDependencyDescriptorMediator(
            ModuleId.newInstance(dep.getGroupId(), dep.getArtifactId()), 
            ExactPatternMatcher.INSTANCE,
            new OverrideDependencyDescriptorMediator(null, dep.getVersion()));
    }
    
    public void addPlugin(PomDependencyMgt plugin) {
        String pluginValue = plugin.getGroupId() + EXTRA_INFO_DELIMITER + plugin.getArtifactId() 
                + EXTRA_INFO_DELIMITER + plugin.getVersion();
        String pluginExtraInfo = (String) ivyModuleDescriptor.getExtraInfo().get("m:maven.plugins");
        if (pluginExtraInfo == null) {
            pluginExtraInfo = pluginValue;
        } else {
            pluginExtraInfo = pluginExtraInfo + "|" + pluginValue;
        }
        ivyModuleDescriptor.getExtraInfo().put("m:maven.plugins", pluginExtraInfo);
    }
    
    public static List /*<PomDependencyMgt>*/ getPlugins(ModuleDescriptor md) {
        List result = new ArrayList();
        String plugins = (String) md.getExtraInfo().get("m:maven.plugins");
        if (plugins == null) {
            return new ArrayList();
        }
        String[] pluginsArray = plugins.split("\\|");
        for (int i = 0; i < pluginsArray.length; i++) {
            String[] parts = pluginsArray[i].split(EXTRA_INFO_DELIMITER);
            result.add(new PomPluginElement(parts[0], parts[1], parts[2]));
        }
        
        return result;
    }
    
    private static class PomPluginElement implements PomDependencyMgt {
        private String groupId;
        private String artifactId;
        private String version;
        
        public PomPluginElement(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
        
        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }
        
        public String getScope() {
            return null;
        }

        public List /*<ModuleId>*/ getExcludedModules() {
            return Collections.EMPTY_LIST; // probably not used?
        }
    }

    private String getDefaultVersion(PomDependencyData dep) {
        ModuleId moduleId = ModuleId.newInstance(dep.getGroupId(), dep.getArtifactId());
        if (ivyModuleDescriptor.getDependencyManagementMap().containsKey(moduleId)) {
            return ((PomDependencyMgt) ivyModuleDescriptor.getDependencyManagementMap().get(
                moduleId)).getVersion();
        }
        String key = getDependencyMgtExtraInfoKeyForVersion(dep.getGroupId(), dep.getArtifactId());
        return (String) ivyModuleDescriptor.getExtraInfo().get(key);
    }

    private String getDefaultScope(PomDependencyData dep) {
        String result;
        ModuleId moduleId = ModuleId.newInstance(dep.getGroupId(), dep.getArtifactId());
        if (ivyModuleDescriptor.getDependencyManagementMap().containsKey(moduleId)) {
            result = ((PomDependencyMgt) ivyModuleDescriptor.getDependencyManagementMap().get(
                moduleId)).getScope();
        } else {
            String key = getDependencyMgtExtraInfoKeyForScope(dep.getGroupId(), dep.getArtifactId());
            result = (String) ivyModuleDescriptor.getExtraInfo().get(key);
        }
        if ((result == null) || !MAVEN2_CONF_MAPPING.containsKey(result)) {
            result = "compile";
        }
        return result;
    }

    private static String getDependencyMgtExtraInfoKeyForVersion(
                                String groupId, String artifaceId) {
        return DEPENDENCY_MANAGEMENT + EXTRA_INFO_DELIMITER + groupId
                + EXTRA_INFO_DELIMITER + artifaceId + EXTRA_INFO_DELIMITER + "version";
    }
    
    private static String getDependencyMgtExtraInfoKeyForScope(String groupId, String artifaceId) {
        return DEPENDENCY_MANAGEMENT + EXTRA_INFO_DELIMITER + groupId
                + EXTRA_INFO_DELIMITER + artifaceId + EXTRA_INFO_DELIMITER + "scope";
    }
    
    private static String getPropertyExtraInfoKey(String propertyName) {
        return PROPERTIES + EXTRA_INFO_DELIMITER + propertyName;
    }

    private static String getDependencyMgtExtraInfoPrefixForExclusion(
                                String groupId, String artifaceId) {
        return DEPENDENCY_MANAGEMENT + EXTRA_INFO_DELIMITER + groupId
                + EXTRA_INFO_DELIMITER + artifaceId + EXTRA_INFO_DELIMITER + "exclusion_";
    }

    private static List /*<ModuleId>*/ getDependencyMgtExclusions(
                                ModuleDescriptor descriptor,
                                String groupId,
                                String artifactId) {
        if (descriptor instanceof PomModuleDescriptor) {
            PomDependencyMgt dependencyMgt = (PomDependencyMgt) ((PomModuleDescriptor) descriptor)
                    .getDependencyManagementMap().get(ModuleId.newInstance(groupId, artifactId));
            if (dependencyMgt != null) {
                return dependencyMgt.getExcludedModules();
            }
        }
        String exclusionPrefix = getDependencyMgtExtraInfoPrefixForExclusion(
                groupId, artifactId);
        List /*<ModuleId>*/ exclusionIds = new LinkedList /*<ModuleId>*/ ();
        Map /*<String,String>*/ extras = descriptor.getExtraInfo();
        for (final Iterator entIter = extras.entrySet().iterator(); entIter.hasNext();) {
            Map.Entry /*<String,String>*/ ent = (Map.Entry) entIter.next();
            String key = (String) ent.getKey();
            if (key.startsWith(exclusionPrefix)) {
                String fullExclusion = (String) ent.getValue();
                String[] exclusionParts = fullExclusion.split(EXTRA_INFO_DELIMITER);
                if (exclusionParts.length != 2) {
                    Message.error(WRONG_NUMBER_OF_PARTS_MSG + exclusionParts.length + " : "
                            + fullExclusion);
                    continue;
                }
                exclusionIds.add(ModuleId.newInstance(exclusionParts[0], exclusionParts[1]));
            }
        }
        return exclusionIds;
    }

    public static Map/*<ModuleId, String version>*/ 
            getDependencyManagementMap(ModuleDescriptor md) {
        Map ret = new LinkedHashMap();
        if (md instanceof PomModuleDescriptor) {
            for (final Iterator iterator = ((PomModuleDescriptor) md).getDependencyManagementMap().entrySet().iterator(); iterator.hasNext();) {
                Map.Entry e = (Entry) iterator.next();
                PomDependencyMgt dependencyMgt = (PomDependencyMgt) e.getValue();
                ret.put(e.getKey(), dependencyMgt.getVersion());
            }
        } else {
            for (Iterator iterator = md.getExtraInfo().entrySet().iterator(); iterator.hasNext();) {
                Map.Entry entry = (Map.Entry) iterator.next();
                String key = (String) entry.getKey();
                if ((key).startsWith(DEPENDENCY_MANAGEMENT)) {
                    String[] parts = key.split(EXTRA_INFO_DELIMITER);
                    if (parts.length != DEPENDENCY_MANAGEMENT_KEY_PARTS_COUNT) {
                        Message.warn("what seem to be a dependency management extra info "
                            + "doesn't match expected pattern: " + key);
                    } else {
                        ret.put(ModuleId.newInstance(parts[1], parts[2]), entry.getValue());
                    }
                }
            }
        }
        return ret;
    }
    
    public static List getDependencyManagements(ModuleDescriptor md) {
        List result = new ArrayList();
        
        if (md instanceof PomModuleDescriptor) {
            result.addAll(((PomModuleDescriptor) md).getDependencyManagementMap().values());
        } else {
            for (Iterator iterator = md.getExtraInfo().entrySet().iterator(); iterator.hasNext();) {
                Map.Entry entry = (Map.Entry) iterator.next();
                String key = (String) entry.getKey();
                if ((key).startsWith(DEPENDENCY_MANAGEMENT)) {
                    String[] parts = key.split(EXTRA_INFO_DELIMITER);
                    if (parts.length != DEPENDENCY_MANAGEMENT_KEY_PARTS_COUNT) {
                        Message.warn("what seem to be a dependency management extra info "
                            + "doesn't match expected pattern: " + key);
                    } else {
                        String versionKey = DEPENDENCY_MANAGEMENT + EXTRA_INFO_DELIMITER + parts[1] 
                                            + EXTRA_INFO_DELIMITER + parts[2] 
                                            + EXTRA_INFO_DELIMITER + "version";
                        String scopeKey = DEPENDENCY_MANAGEMENT + EXTRA_INFO_DELIMITER + parts[1] 
                                            + EXTRA_INFO_DELIMITER + parts[2] 
                                            + EXTRA_INFO_DELIMITER + "scope";
    
                        String version = (String) md.getExtraInfo().get(versionKey);
                        String scope = (String) md.getExtraInfo().get(scopeKey);
                        
                        List /*<ModuleId>*/ exclusions = getDependencyMgtExclusions(md, parts[1], parts[2]);
                        result.add(new DefaultPomDependencyMgt(parts[1], parts[2], version, scope, exclusions));
                    }
                }
            }
        }
        return result;
    }


    public void addExtraInfos(Map extraAttributes) {
        for (Iterator it = extraAttributes.entrySet().iterator(); it.hasNext();) {
            Map.Entry entry = (Entry) it.next();
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            addExtraInfo(key, value);
        }
    }


    private void addExtraInfo(String key, String value) {
        if (!ivyModuleDescriptor.getExtraInfo().containsKey(key)) {
            ivyModuleDescriptor.addExtraInfo(key, value);
        }
    }

    
    
    public static Map extractPomProperties(Map extraInfo) {
        Map r = new HashMap();
        for (Iterator it = extraInfo.entrySet().iterator(); it.hasNext();) {
            Map.Entry extraInfoEntry = (Map.Entry) it.next();
            if (((String) extraInfoEntry.getKey()).startsWith(PROPERTIES)) {
                String prop = ((String) extraInfoEntry.getKey()).substring(PROPERTIES.length()
                        + EXTRA_INFO_DELIMITER.length());
                r.put(prop, extraInfoEntry.getValue());
            }
        }
        return r;
    }


    public void addProperty(String propertyName, String value) {
        addExtraInfo(getPropertyExtraInfoKey(propertyName), value);
    }

    public Artifact getMainArtifact() {
        return mainArtifact;
    }

    public Artifact getSourceArtifact() {
        return new MDArtifact(
            ivyModuleDescriptor, mrid.getName(), "source", "jar", 
            null, Collections.singletonMap("m:classifier", "sources"));
    }

    public Artifact getSrcArtifact() {
        return new MDArtifact(
            ivyModuleDescriptor, mrid.getName(), "source", "jar", 
            null, Collections.singletonMap("m:classifier", "src"));
    }

    public Artifact getJavadocArtifact() {
        return new MDArtifact(
            ivyModuleDescriptor, mrid.getName(), "javadoc", "jar", 
            null, Collections.singletonMap("m:classifier", "javadoc"));
    }

    public void addSourceArtifact() {
        ivyModuleDescriptor.addArtifact("sources", getSourceArtifact());
    }
    
    public void addSrcArtifact() {
        ivyModuleDescriptor.addArtifact("sources", getSrcArtifact());
    }

    public void addJavadocArtifact() {
        ivyModuleDescriptor.addArtifact("javadoc", getJavadocArtifact());
    }

    /**
     * <code>DependencyDescriptor</code> that provides access to the original <code>PomDependencyData</code>.
     */
    public static class PomDependencyDescriptor extends DefaultDependencyDescriptor {
        private final PomDependencyData pomDependencyData;

        private PomDependencyDescriptor(PomDependencyData pomDependencyData,
                ModuleDescriptor moduleDescriptor, ModuleRevisionId revisionId) {
            super(moduleDescriptor, revisionId, true, false, true);
            this.pomDependencyData = pomDependencyData;
        }

        /**
         * Get PomDependencyData.
         * @return PomDependencyData
         */
        public PomDependencyData getPomDependencyData() {
            return pomDependencyData;
        }
    }

    public static class PomModuleDescriptor extends DefaultModuleDescriptor {
        private final Map/*<ModuleId, PomDependencyMgt>*/ dependencyManagementMap = new HashMap();

        public PomModuleDescriptor(ModuleDescriptorParser parser, Resource res) {
            super(parser, res);
        }

        public void addDependencyManagement(PomDependencyMgt dependencyMgt) {
            dependencyManagementMap.put(ModuleId.newInstance(dependencyMgt.getGroupId(), dependencyMgt.getArtifactId()), dependencyMgt);
        }

        public Map getDependencyManagementMap() {
            return dependencyManagementMap;
        }
    }
}
