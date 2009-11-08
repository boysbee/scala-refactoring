package scala.tools.refactor.tests

import utils.TestHelper
import org.junit.Test
import junit.framework.TestCase
import org.junit.Assert._
import utils.Compiler
import scala.tools.refactor.printer._

@Test
class WhiteSpaceSplitterTest extends TestCase with TestHelper {

  def testClassParameters() = {
    "class A ( i: /*c*/Int, s: String)" splitsInto "class ▒A (▒ i: /*c*/▒Int▒s: ▒String▒)"
  }
  
}
