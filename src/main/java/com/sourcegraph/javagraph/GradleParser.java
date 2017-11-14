package com.sourcegraph.javagraph;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/*
 * Static analysis for build.gradle files to parse out config, metadata and dependencies
 */
// TODO: support all methods listed here
// https://docs.gradle.org/current/userguide/dependency_management.html#sec:how_to_declare_your_dependencies
public class GradleParser {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(GradleParser.class);
	
	private SourceUnit unit;
	private String gradleFile;
	private String directory;
	private ArrayList<String> rawDepList = new ArrayList<>();
	
	/* 
	 * Handles top level tasks
	 * */
    private CodeVisitorSupport gradleTaskParser = new CodeVisitorSupport() {            
        @Override
        public void visitMethodCallExpression(MethodCallExpression call){
        		String methodName = call.getMethodAsString().toString().trim();
        		if (methodName.equalsIgnoreCase("dependencies")) {
        			LOGGER.debug("Found dependencies block");
        			call.getArguments().visit(gradleDependencyParser);
        		} else if (methodName.equalsIgnoreCase("repositories")) {
        			// TODO: parse config and return to parent project
        		} else if (!methodName.equalsIgnoreCase("buildscript")) {
        			LOGGER.debug("Found gradle task: {}", methodName);
        			// ignore, android build script as those are only test deps
        			call.getArguments().visit(this);
        		}
        }
        
        // TODO: add config block 
        // TODO: look at current src unit and see what data we can populate
    };
    
	/* 
	 * Handles parsing dependency block
	 * */
    private CodeVisitorSupport gradleDependencyParser = new CodeVisitorSupport() {
    		private String currentDependencyType;
    		private String currentDependencyKey;
    		// NOTE: this must be called an extra time to flush out the hashmap
    		private HashMap<String, String> dependencyGroup;
    		
        private CodeVisitorSupport gradleArtifactParser = new CodeVisitorSupport() {  	
    			@Override
            public void visitMethodCallExpression(MethodCallExpression call) {
        			// TODO: handle other methods for defining artifacts 
    				String methodType = call.getMethodAsString();
    				if (methodType == "files" || methodType == "fileTree") return;
    				//            		call.getArguments().visit(gradleArtifactParser);
            }
    		
    	    	    @Override
    	    	    public void visitConstantExpression(ConstantExpression expr) {
    	    	    		String dependencyId = expr.getValue().toString().trim();
    	    	    		
    	    	    		if (dependencyId.equals("group") || dependencyId.equals("name") || dependencyId.equals("version") || dependencyId.equals("transitive")) {
    	    	    			currentDependencyKey = dependencyId;
    	    	    			return;
    	    	    		} else if (currentDependencyKey != null) {
    	    	    			// initialize a dependency group
    	    	    			if (dependencyGroup == null) {
    	    	    				dependencyGroup = new HashMap<>();
    	    	    			}
    	    	    			dependencyGroup.put(currentDependencyKey.toString(), dependencyId);
    	    	    			currentDependencyKey = null;
    	    	    			return;
    	    	    		} else if (dependencyGroup != null) {
    	    	    			// terminate the group; note this is trailing from last 
    	    	    			// TODO; this logic needs to be called one more time to flush trailing unterminated dep groups
    	    	    			addDependency(dependencyGroup.get("group") + ":" + dependencyGroup.get("name") + ":" + dependencyGroup.get("version"), currentDependencyType);
    	    	    			dependencyGroup = null;
    	    	    		}
    	    	    		if (dependencyId.equals("true") || dependencyId.equals("false")) return; // reserved parsing artifacts for "transitive" setting
    	    	    		
    	    	    		addDependency(dependencyId, currentDependencyType);
	    	    		
	    	    		// java plugin: api, impelmentation, compileONly, debug, debugImplementation
	    	    		// namespaced: freeCompile, freeDebug, freeTestCompile, androidTestCompile etc... 
	    	    		// android specific: compile, provided, apk
	    	    		
	    	    		// note will hasGraph include non scopes?
    	    	    }
        };
            
    		@Override
        public void visitMethodCallExpression(MethodCallExpression call) {
    			String configurationType = call.getMethodAsString();
    			this.currentDependencyType = configurationType;
        		call.getArguments().visit(gradleArtifactParser);
        }
    };
    
    private void addDependency(String dependencyId, String config) {
    		String[] dependencyParts = dependencyId.split(":");
    		if (dependencyParts.length == 3) {
	    		RawDependency newDependency = new RawDependency(dependencyParts[0], dependencyParts[1], dependencyParts[2], config, this.gradleFile, PathUtil.CWD.relativize(Paths.get(this.gradleFile)).toString());
	    		unit.Dependencies.add(newDependency);
    		} else {
    			LOGGER.warn("Tried to parse invalid dependency {} from config {}", dependencyId, config);
    		}
    }
    
    public GradleParser(Path build) throws IOException {
    		this.gradleFile = build.toString();
    		this.directory = build.getParent().toString();
    		unit = new SourceUnit();
    		unit.Dependencies = new ArrayList<>();
    	
    		byte[] encoded = Files.readAllBytes(build);
		org.codehaus.groovy.control.SourceUnit gradleUnit = org.codehaus.groovy.control.SourceUnit.create("gradle", new String(encoded, StandardCharsets.UTF_8));
		gradleUnit.parse();
		gradleUnit.completePhase();
		gradleUnit.convert();
		
		// Run task parser
		gradleUnit.getAST().getStatementBlock().visit(gradleTaskParser);
		
		
        unit.Type = SourceUnit.DEFAULT_TYPE;
        unit.Name = build.getParent().getFileName().toString();
        unit.Dir = this.directory;
        
//        if (info.buildFile != null) {
//            unit.Data.put("GradleFile", PathUtil.normalize(
//                    projectRoot.relativize(PathUtil.CWD.resolve(info.buildFile)).normalize().toString()));
//        } else {
//            unit.Data.put("GradleFile", StringUtils.EMPTY);
//        }
//        unit.Data.put("Description", info.attrs.description);
//        if (!StringUtils.isEmpty(info.sourceVersion)) {
//            unit.Data.put("SourceVersion", info.sourceVersion);
//        }
//        if (!StringUtils.isEmpty(info.sourceEncoding)) {
//            unit.Data.put("SourceEncoding", info.sourceEncoding);
//        }
//
//        if (info.androidSdk != null) {
//            unit.Data.put("Android", info.androidSdk);
//        }

        // leave only existing files
//        unit.Files = new LinkedList<>();
//        for (String sourceFile : info.sources) {
//            File f = PathUtil.CWD.resolve(sourceFile).toFile();
//            if (f.isFile()) {
//                // including only existing files to make 'make' tool happy
//                unit.Files.add(f.getAbsolutePath());
//            }
//        }
        
		
		// TODO: get metadata and android v
    }
    
    public SourceUnit getUnit() {
    		return this.unit;
    }
	
}
