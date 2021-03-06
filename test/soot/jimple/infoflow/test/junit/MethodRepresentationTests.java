package soot.jimple.infoflow.test.junit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;
/**
 * check the conversion of Soot's String representation into our internal data format. 
 */
public class MethodRepresentationTests {

	@Test
	public void testParser(){
		String s = "<soot.jimple.infoflow.test.TestNoMain: java.lang.String function1()>";
		
		SootMethodRepresentationParser parser = SootMethodRepresentationParser.v();
		SootMethodAndClass result = parser.parseSootMethodString(s);
		
		assertEquals("soot.jimple.infoflow.test.TestNoMain", result.getClassName());
		assertEquals("function1", result.getMethodName());
		assertEquals("java.lang.String", result.getReturnType());
	}
	
}
