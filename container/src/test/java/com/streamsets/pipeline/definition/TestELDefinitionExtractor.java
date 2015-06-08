/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.definition;

import com.google.common.collect.ImmutableList;
import com.streamsets.pipeline.api.ElConstant;
import com.streamsets.pipeline.api.ElFunction;
import com.streamsets.pipeline.api.ElParam;
import com.streamsets.pipeline.el.ElConstantDefinition;
import com.streamsets.pipeline.el.ElFunctionDefinition;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class TestELDefinitionExtractor {

  public static class Empty {
  }

  public static class Ok {

    @ElFunction(prefix = "p", name = "f", description = "ff")
    public static String f(@ElParam("x") int x) {
      return null;
    }

    @ElConstant(name = "C", description = "CC")
    public static final String C = "c";

  }

  public static class Fail1 {

    @ElFunction(prefix = "p", name = "f")
    public String f() {
      return null;
    }

    @ElConstant(name = "C", description = "CC")
    public final String C = "c";

  }

  public static class Fail2 {

    @ElFunction(prefix = "p", name = "f")
    private static String f() {
      return null;
    }

    @ElConstant(name = "C", description = "CC")
    protected static final String C = "c";

  }

  @Test
  public void testExtractionEmpty() {
    Assert.assertTrue(ELDefinitionExtractor.get().extractFunctions(ImmutableList.<Class>of(Empty.class), "").isEmpty());
    Assert.assertTrue(ELDefinitionExtractor.get().extractConstants(ImmutableList.<Class>of(Empty.class), "").isEmpty());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testExtractionFail1Function() {
    ELDefinitionExtractor.get().extractFunctions(new Class[]{Fail1.class}, "x");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testExtractionFail2Function() {
    ELDefinitionExtractor.get().extractFunctions(new Class[]{Fail2.class}, "x");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testExtractionFail1Constant() {
    ELDefinitionExtractor.get().extractConstants(new Class[]{Fail1.class}, "x");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testExtractionFail2Constant() {
    ELDefinitionExtractor.get().extractConstants(new Class[]{Fail2.class}, "x");
  }

  @Test
  public void testExtractionFunctions() {
    List<ElFunctionDefinition> functions = ELDefinitionExtractor.get().extractFunctions(ImmutableList.<Class>of(Ok.class), "x");
    Assert.assertEquals(1, functions.size());
    Assert.assertEquals("p:f", functions.get(0).getName());
    Assert.assertEquals("p", functions.get(0).getGroup());
    Assert.assertEquals("ff", functions.get(0).getDescription());
    Assert.assertEquals(String.class.getSimpleName(), functions.get(0).getReturnType());
    Assert.assertEquals(1, functions.get(0).getElFunctionArgumentDefinition().size());
    Assert.assertEquals("x", functions.get(0).getElFunctionArgumentDefinition().get(0).getName());
    Assert.assertEquals(Integer.TYPE.getSimpleName(),
                        functions.get(0).getElFunctionArgumentDefinition().get(0).getType());
  }

  @Test
  public void testExtractionConstants() {
    List<ElConstantDefinition> constants = ELDefinitionExtractor.get().extractConstants(ImmutableList.<Class>of(Ok.class), "x");
    Assert.assertEquals(1, constants.size());
    Assert.assertEquals("C", constants.get(0).getName());
    Assert.assertEquals("CC", constants.get(0).getDescription());
    Assert.assertEquals(String.class.getSimpleName(), constants.get(0).getReturnType());
  }
}
