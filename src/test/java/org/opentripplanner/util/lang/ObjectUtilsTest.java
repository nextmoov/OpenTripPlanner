package org.opentripplanner.util.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ObjectUtilsTest {

  @Test
  void ifNotNull() {
    assertEquals("exp", ObjectUtils.ifNotNull("exp", "default"));
    assertEquals("default", ObjectUtils.ifNotNull(null, "default"));
    assertNull(ObjectUtils.ifNotNull(null, null));
  }

  @Test
  void ifNotNullFunctional() {
    var i = new AtomicInteger(5);
    assertEquals(5, ObjectUtils.ifNotNull(i, AtomicInteger::get, 7));
    assertEquals(7, ObjectUtils.ifNotNull(null, AtomicInteger::get, 7));

    // Default is null
    assertEquals(5, ObjectUtils.ifNotNull(i, AtomicInteger::get, null));
    assertNull(ObjectUtils.ifNotNull(null, AtomicInteger::get, null));

    var o = new AtomicReference<String>(null);
    assertEquals("DEFAULT", ObjectUtils.ifNotNull(o, AtomicReference::get, "DEFAULT"));
    assertNull(ObjectUtils.ifNotNull(o, AtomicReference::get, null));
  }
}
