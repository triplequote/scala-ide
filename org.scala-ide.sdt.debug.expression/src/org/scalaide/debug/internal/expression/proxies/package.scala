/*
 * Copyright (c) 2014 Contributor. All rights reserved.
 */
package org.scalaide.debug.internal.expression

/**
 * Contains interface for all proxies [[org.scalaide.debug.internal.expression.proxies.JdiProxy]]
 * and its implementation for String, Unit and new classes.
 *
 * JdiProxy is used every time value or object are used in evaluated expression. It proxies every method call to debugged
 * machine using enclosed JdiContext.
 */
package object proxies {

  /** Name of this package, used in reflective compilation of expressions. */
  val name = "org.scalaide.debug.internal.expression.proxies"
}