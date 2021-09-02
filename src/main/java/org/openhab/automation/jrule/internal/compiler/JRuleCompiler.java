/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.jrule.internal.compiler;

import static org.openhab.automation.jrule.internal.JRuleConstants.*;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import javax.tools.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.openhab.automation.jrule.internal.JRuleConfig;
import org.openhab.automation.jrule.internal.JRuleConstants;
import org.openhab.automation.jrule.internal.JRuleUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link JRuleCompiler}
 *
 * @author Joseph (Seaside) Hagberg - Initial contribution
 */
public class JRuleCompiler {

    private static final String JAVA_CLASS_PATH_PROPERTY = "java.class.path";
    private static final String CLASSPATH_OPTION = "-classpath";
    public static final String JAR_JRULE_NAME = "jrule.jar";
    public static final String JAR_JRULE_ITEMS_NAME = "jrule-items.jar";

    private final Logger logger = LoggerFactory.getLogger(JRuleCompiler.class);

    private final JRuleConfig jRuleConfig;

    public JRuleCompiler(JRuleConfig jRuleConfig) {
        this.jRuleConfig = jRuleConfig;
    }

    public void loadClasses(ClassLoader classLoader, File classFolder, String classBasePackage,
            boolean createInstance) {
        try {
            final Collection<File> classFiles = FileUtils.listFiles(classFolder, new String[] { CLASS_FILE_EXTENSION },
                    true);
            if (classFiles.isEmpty()) {
                logger.info("Found no user defined java rules to load into memory in folder: {}",
                        classFolder.getAbsolutePath());
                return;
            }
            logger.info("Number of Java Rules classes to load in to memory: {} folder: {}", classFiles.size(),
                    classFolder.getAbsolutePath());

            classFiles.forEach(classFile -> {

                String fullyQualifiedClassName = convertToClassName(classFile, classBasePackage);

                logger.debug("Loading instance for class: {}, fully qualified: {}, from file: {}", classFile.getName(),
                        fullyQualifiedClassName, classFile);
                try {
                    Class<?> loadedClass = classLoader.loadClass(fullyQualifiedClassName);
                    logger.debug("Loaded class with classLoader: {}", classFile.getName());

                    if (createInstance) {
                        if (Modifier.isAbstract(loadedClass.getModifiers())) {
                            logger.debug("Not creating and instance of abstract class: {}", classFile.getName());
                        } else {
                            try {
                                Constructor<?> declaredConstructor = loadedClass.getDeclaredConstructor();
                                if (declaredConstructor.getParameterCount() > 0) {
                                    logger.debug("Not creating instance of class without default constructor: {}",
                                            classFile.getName());
                                } else {
                                    final Object obj = declaredConstructor.newInstance();
                                    logger.debug("Created instance: {} obj: {}", classFile.getName(), obj);
                                }

                            } catch (Exception x) {
                                logger.debug("Could not create create instance using default constructor: {}",
                                        classFile.getName());
                            }
                        }
                    }
                } catch (ClassNotFoundException | IllegalArgumentException | SecurityException e) {
                    logger.error("Could not load class", e);
                }
            });
        } catch (Exception e) {
            logger.error("error instance", e);
        }
    }

    static String convertToClassName(File classFile, String classBasePackage) {
        String classFileDirectoryPath = classFile.getParentFile().getAbsolutePath();
        String classFileDirectoryPathAsPackage = StringUtils.replace(classFileDirectoryPath, File.separator, ".");
        String classItemPackage = StringUtils.substring(classFileDirectoryPathAsPackage,
                classFileDirectoryPathAsPackage.indexOf(classBasePackage));

        return classItemPackage + "." + JRuleUtil.removeExtension(classFile.getName(), CLASS_FILE_TYPE);
    }

    public void compile(File javaSourceFile, String classPath) {
        compile(Arrays.asList(javaSourceFile), classPath);
    }

    public void compile(Collection<File> javaSourceFiles, String classPath) {
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        final List<String> optionList = new ArrayList<String>();
        optionList.add(CLASSPATH_OPTION);
        optionList.add(classPath);
        optionList.add("-d");
        optionList.add(jRuleConfig.getRuleClassesDirectory());
        logger.debug("Compiling classes using classpath: {}", classPath);
        javaSourceFiles.stream().filter(javaSourceFile -> javaSourceFile.exists() && javaSourceFile.canRead())
                .forEach(javaSourceFile -> {
                    logger.debug("Compiling java Source file: {}", javaSourceFile);
                });

        final Iterable<? extends JavaFileObject> compilationUnit = fileManager
                .getJavaFileObjectsFromFiles(javaSourceFiles);
        final JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, optionList, null,
                compilationUnit);
        try {
            if (task.call()) {
                logger.debug("Compilation of classes successfull!");
            } else {
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    logger.info("Error on line {} in {}", diagnostic.getLineNumber(), diagnostic.getSource().toUri());
                }
            }
            fileManager.close();
        } catch (Exception x) {
            logger.error("error", x);
        }
    }

    public File[] getJavaSourceItemsFromFolder(File folder) {
        return folder.listFiles(JRuleFileNameFilter.JAVA_FILTER);
    }

    public void compileItemsInFolder(File itemsFolder) {
        final String itemsClassPath = System.getProperty(JAVA_CLASS_PATH_PROPERTY) + File.pathSeparator
                + getJarPath(JAR_JRULE_NAME);
        logger.debug("Compiling items in folder: {}", itemsFolder.getAbsolutePath());
        final File[] javaItems = getJavaSourceItemsFromFolder(itemsFolder);
        final File[] classItems = itemsFolder.listFiles(JRuleFileNameFilter.CLASS_FILTER);
        final Set<String> classNames = new HashSet<>();
        Arrays.stream(classItems).forEach(classItem -> classNames
                .add(JRuleUtil.removeExtension(classItem.getName(), JRuleConstants.CLASS_FILE_TYPE)));

        logger.debug("ClassNameSetSize: {}", classNames.size());
        Arrays.stream(javaItems)
                .filter(javaItem -> !classNames.contains(JRuleUtil.removeExtension(javaItem.getName(), JAVA_FILE_TYPE)))
                .forEach(javaItem -> compile(javaItem, itemsClassPath));
        classNames.clear();
    }

    public String getJarPath(String jarName) {
        return new StringBuilder().append(jRuleConfig.getJarDirectory()).append(File.separator).append(jarName)
                .toString();
    }

    public void compileItems() {
        compileItemsInFolder(new File(jRuleConfig.getItemsDirectory()));
    }

    public void compileRules() {
        String rulesClassPath = //
                System.getProperty(JAVA_CLASS_PATH_PROPERTY) + File.pathSeparator //
                        + getJarPath(JAR_JRULE_ITEMS_NAME) + File.pathSeparator //
                        + getJarPath(JAR_JRULE_NAME) + File.pathSeparator; //
        String extLibPath = getExtLibPaths();
        logger.debug("extLibPath: {}", extLibPath);
        if (extLibPath != null && !extLibPath.isEmpty()) {
            rulesClassPath = rulesClassPath.concat(extLibPath);
        }
        logger.debug("Compiling rules in folder: {}", jRuleConfig.getRulesDirectory());
        File rulesDirectory = new File(jRuleConfig.getRulesDirectory());
        // delete all existing class files, otherwise orphaned rules will stay
        FileUtils.listFiles(rulesDirectory, new String[] { CLASS_FILE_EXTENSION }, true)
                .forEach(FileUtils::deleteQuietly);

        Collection<File> javaFiles = FileUtils.listFiles(rulesDirectory, new String[] { JAVA_FILE_EXTENSION }, true);
        if (javaFiles.isEmpty()) {
            logger.info("Found no java rules to compile and use in folder: {}, no rules are loaded",
                    jRuleConfig.getRulesDirectory());
            return;
        }
        compile(javaFiles, rulesClassPath);
    }

    public List<URL> getExtLibsAsUrls() {
        try {
            File[] extLibsFiles = getExtLibsAsFiles();
            final List<URL> urlList = Arrays.stream(extLibsFiles).map(f -> getUrl(f)).collect(Collectors.toList());
            return urlList;
        } catch (Exception x) {
            logger.error("Failed to get extLib urls");
            return new ArrayList<>();
        }
    }

    private URL getUrl(File f) {
        try {
            return f.toURI().toURL();
        } catch (MalformedURLException e) {
            logger.error("Failed to convert to URL: {}", f.getAbsolutePath(), e);
        }
        return null;
    }

    public File[] getExtLibsAsFiles() {
        return new File(jRuleConfig.getExtlibDirectory()).listFiles(JRuleFileNameFilter.JAR_FILTER);
    }

    private String getExtLibPaths() {
        final File[] extLibs = getExtLibsAsFiles();
        final StringBuilder builder = new StringBuilder();
        if (extLibs != null && extLibs.length > 0) {
            Arrays.stream(extLibs).forEach(extLib -> builder.append(createJarPath(extLib)));
        }
        return builder.toString();
    }

    private String createJarPath(File extLib) {
        if (!extLib.canRead()) {
            logger.error("Invalid permissions for external lib jar, ignored: {}", extLib.getAbsolutePath());
            return JRuleConstants.EMPTY;
        }
        return extLib.getAbsolutePath().concat(File.pathSeparator);
    }

    private static class JRuleFileNameFilter implements FilenameFilter {

        private static final JRuleFileNameFilter JAVA_FILTER = new JRuleFileNameFilter(JAVA_FILE_TYPE);
        private static final JRuleFileNameFilter CLASS_FILTER = new JRuleFileNameFilter(JRuleConstants.CLASS_FILE_TYPE);
        private static final JRuleFileNameFilter JAR_FILTER = new JRuleFileNameFilter(JRuleConstants.JAR_FILE_TYPE);

        private final String fileType;

        public JRuleFileNameFilter(String fileType) {
            this.fileType = fileType;
        }

        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(fileType);
        }
    }
}
