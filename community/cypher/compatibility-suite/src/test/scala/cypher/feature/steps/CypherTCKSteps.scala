/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package cypher.feature.steps

import java.io.File
import java.nio.file.{Files, Path}
import java.util

import _root_.cucumber.api.DataTable
import _root_.cucumber.api.scala.{EN, ScalaDsl}
import cypher.cucumber.db.DatabaseConfigProvider.cypherConfig
import cypher.cucumber.db.DatabaseLoader
import cypher.feature.parser.{MatcherMatchingSupport, constructResultMatcher, parseParameters, statisticsParser}
import org.neo4j.graphdb._
import org.neo4j.graphdb.factory.{GraphDatabaseBuilder, GraphDatabaseFactory, GraphDatabaseSettings}
import org.neo4j.test.TestGraphDatabaseFactory
import org.opencypher.tools.tck.TCKStepDefinitions._
import org.scalatest.{FunSuiteLike, Matchers}

import scala.util.{Failure, Success, Try}

class CypherTCKSteps extends FunSuiteLike with Matchers with ScalaDsl with EN with MatcherMatchingSupport {

  val Background = new Step("Background")

  // Stateful
  var graph: GraphDatabaseService = null
  var result: Try[Result] = null
  var params: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]()

  Before() { _ =>
    initEmpty()
  }

  After() { _ =>
    // TODO: postpone this till the last scenario
    graph.shutdown()
  }

  Background(BACKGROUND) {
    // do nothing, but necessary for the scala match
  }

  Given(NAMED_GRAPH) { (dbName: String) =>
    val builder = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(DatabaseLoader(dbName))
    graph = loadConfig(builder).newGraphDatabase()
  }

  Given(ANY) {
    // We could do something fancy here, like randomising a state,
    // in order to guarantee that we aren't implicitly relying on an empty db.
    initEmpty()
  }

  Given(EMPTY) {
    initEmpty()
  }

  And(INIT_QUERY) { (query: String) =>
    // side effects are necessary for setting up graph state
    graph.execute(query)
  }

  And(PARAMETERS) { (values: DataTable) =>
    params = parseParameters(values)
  }

  When(EXECUTING_QUERY) { (query: String) =>
    result = Try { graph.execute(query, params) }
  }

  Then(EXPECT_RESULT) { (expectedTable: DataTable) =>
    val matcher = constructResultMatcher(expectedTable)

    matcher should accept(successful(result))
  }

  Then(EXPECT_ERROR) { (status: String, time: String, detail: String) =>
    if (time == "runtime") {
      result match {
        case Success(triedResult) =>
          val consumptionResult = Try { while (triedResult.hasNext) triedResult.next() }
          consumptionResult match {
            case Failure(e: QueryExecutionException) =>
              s"Neo.ClientError.Statement.$status" should equal(e.getStatusCode)

              if (e.getMessage.matches("Expected .+ to be a java.lang.String, but it was a .+")) detail should equal("MapElementAccessByNonString")
              else if (e.getMessage.matches("Expected .+ to be a java.lang.Number, but it was a .+")) detail should equal("ListElementAccessByNonInteger")
              else if (e.getMessage.matches(".+ is not a collection or a map. Element access is only possible by performing a collection lookup using an integer index, or by performing a map lookup using a string key .+")) detail should equal("InvalidElementAccess")
              else fail(s"Unknown runtime error: $e")

            case Failure(e) =>
              fail(s"Unknown runtime error: $e")

            case _: Success[_] =>
              fail("No runtime error was raised")
          }
        case Failure(e) =>
          fail(s"An unexpected compile time error was raised: $e")
      }
    }
  }

  Then(EXPECT_SORTED_RESULT) { (expectedTable: DataTable) =>
    val matcher = constructResultMatcher(expectedTable)

    matcher should acceptOrdered(successful(result))
  }

  Then(EXPECT_EMPTY_RESULT) {
    successful(result).hasNext shouldBe false
  }

  And(SIDE_EFFECTS) { (expectations: DataTable) =>
    statisticsParser(expectations) should accept(successful(result).getQueryStatistics)
  }

  private def successful(value: Try[Result]): Result = value match {
    case Success(r) => r
    case Failure(e) => fail(s"Expected successful result, but got error: $result")
  }

  private def initEmpty() =
    if (graph == null || !graph.isAvailable(1L)) {
      val builder = new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder()
      graph = loadConfig(builder).newGraphDatabase()
    }

  private def loadConfig(builder: GraphDatabaseBuilder): GraphDatabaseBuilder = {
    val directory: Path = Files.createTempDirectory("tls")
    builder.setConfig(GraphDatabaseSettings.pagecache_memory, "8M")
    builder.setConfig(GraphDatabaseSettings.auth_store, new File(directory.toFile, "auth").getAbsolutePath)
    builder.setConfig("dbms.security.tls_key_file", new File(directory.toFile, "key.key").getAbsolutePath)
    builder.setConfig("dbms.security.tls_certificate_file", new File(directory.toFile, "cert.cert").getAbsolutePath)
    cypherConfig().map { case (s, v) => builder.setConfig(s, v) }
    builder
  }
}
