package soot.jimple.infoflow.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import soot.jimple.infoflow.data.SootMethodAndClass;
/**
 * handles conversion from the string representation of SootMethod to our internal format {@link soot.jimple.infoflow.data.SootMethodAndClass}
 *
 */
public class SootMethodRepresentationParser {
	
	private static final SootMethodRepresentationParser instance = new SootMethodRepresentationParser();
	
	private SootMethodRepresentationParser() {
		
	}
	
	public static SootMethodRepresentationParser v() {
		return instance;
	}
	
	/**
	 * parses a string in soot representation, for example:
	 * <soot.jimple.infoflow.test.TestNoMain: java.lang.String function1()>
	 * <soot.jimple.infoflow.test.TestNoMain: void functionCallOnObject()>
	 * <soot.jimple.infoflow.test.TestNoMain: java.lang.String function2(java.lang.String,java.lang.String)>
	 * @param parseString The method signature to parse
	 */
	public SootMethodAndClass parseSootMethodString(String parseString){
		if(!parseString.startsWith("<") || !parseString.endsWith(">")){
			throw new IllegalArgumentException("Illegal format of " +parseString +" (should use soot method representation)");
		}
		String name = "";
		String className = "";
		String returnType = "";
		Pattern pattern = Pattern.compile("<(.*?):");
        Matcher matcher = pattern.matcher(parseString);
        if(matcher.find()){
        	className = matcher.group(1);
        }
        pattern = Pattern.compile(": (.*?) ");
        matcher = pattern.matcher(parseString);
        if(matcher.find()){
        	returnType =  matcher.group(1);
        	//remove the string contents that are already found so easier regex is possible
        	parseString = parseString.substring(matcher.end(1));        	
        }
        pattern = Pattern.compile(" (.*?)\\(");
        matcher = pattern.matcher(parseString);
        if(matcher.find()){
        	name = matcher.group(1);
        }
        List<String> paramList = new ArrayList<String>();
        pattern = Pattern.compile("\\((.*?)\\)");
        matcher = pattern.matcher(parseString);
        if(matcher.find()){
        	String params = matcher.group(1);
        	for (String param : params.split(","))
       			paramList.add(param.trim());
        }
        return new SootMethodAndClass(name, className, returnType, paramList);
       
	}
	//returns classname and unresolved! method names and return types and parameters
	public HashMap<String, List<String>> parseClassNames(List<String> methods, boolean subSignature){
		HashMap<String, List<String>> result = new HashMap<String,  List<String>>();
		Pattern pattern = Pattern.compile("^\\s*<(.*?):\\s*(.*?)>\\s*$");
		for(String parseString : methods){
			//parse className:
			String className = "";
	        Matcher matcher = pattern.matcher(parseString);
	        if(matcher.find()){
	        	className = matcher.group(1);
	        	String params = "";
				if(subSignature)
					params = matcher.group(2);
				else
					params = parseString;
				
				if(result.containsKey(className))
					result.get(className).add(params);
				else {
					List<String> methodList = new ArrayList<String>(); 
					methodList.add(params);
					result.put(className, methodList);
				}
	        }
		}
		return result;
	}

}
