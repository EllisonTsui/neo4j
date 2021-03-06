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
package org.neo4j.cypher.internal.compiler.v3_1.pipes

import org.neo4j.cypher.internal.compiler.v3_1._
import org.neo4j.cypher.internal.compiler.v3_1.commands.expressions.AggregationExpression
import org.neo4j.cypher.internal.compiler.v3_1.pipes.aggregation.AggregationFunction
import org.neo4j.cypher.internal.compiler.v3_1.planDescription.InternalPlanDescription.Arguments
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable

import scala.collection.mutable.{Map => MutableMap}

// Eager aggregation means that this pipe will eagerly load the whole resulting sub graphs before starting
// to emit aggregated results.
// Cypher is lazy until it can't - this pipe will eagerly load the full match
case class EagerAggregationPipe(source: Pipe, keyExpressions: Set[String], aggregations: Map[String, AggregationExpression])
                               (val estimatedCardinality: Option[Double] = None)
                               (implicit pipeMonitor: PipeMonitor) extends PipeWithSource(source, pipeMonitor) with RonjaPipe with NoEffectsPipe {

  val symbols: SymbolTable = createSymbols()

  private def createSymbols() = {
    val keyVariables = keyExpressions.map(id => id -> source.symbols.evaluateType(id, CTAny)).toMap
    val aggrVariables = aggregations.map {
      case (innerId, exp) => innerId -> exp.getType(source.symbols)
    }

    SymbolTable(keyVariables ++ aggrVariables)
  }

  protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState) = {
    //register as parent so that stats are associated with this pipe
    state.decorator.registerParentPipe(this)

    // This is the temporary storage used while the aggregation is going on
    val result = MutableMap[NiceHasher, (ExecutionContext, Seq[AggregationFunction])]()
    val keyNames: Seq[String] = keyExpressions.toSeq
    val aggregationNames: Seq[String] = aggregations.keys.toSeq
    val mapSize = keyNames.size + aggregationNames.size

    def createResults(key: NiceHasher, aggregator: scala.Seq[AggregationFunction], ctx: ExecutionContext): ExecutionContext = {
      val newMap = MutableMaps.create(mapSize)

      //add key values
      (keyNames zip key.original).foreach(newMap += _)

      //add aggregated values
      (aggregationNames zip aggregator.map(_.result)).foreach(newMap += _)

      ctx.newFromMutableMap(newMap)
    }

    def createEmptyResult(params: Map[String, Any]): Iterator[ExecutionContext] = {
      val newMap = MutableMaps.empty
      val aggregationNamesAndFunctions = aggregationNames zip aggregations.map(_._2.createAggregationFunction.result)

      aggregationNamesAndFunctions.toMap
        .foreach { case (name, zeroValue) => newMap += name -> zeroValue}
      Iterator.single(ExecutionContext(newMap))
    }

    input.foreach(ctx => {
      val groupValues: NiceHasher = new NiceHasher(keyNames.map(ctx))
      val aggregateFunctions: Seq[AggregationFunction] = aggregations.map(_._2.createAggregationFunction).toSeq
      val (_, functions) = result.getOrElseUpdate(groupValues, (ctx, aggregateFunctions))
      functions.foreach(func => func(ctx)(state))
    })

    if (result.isEmpty && keyNames.isEmpty) {
      createEmptyResult(state.params)
    } else {
      result.map {
        case (key, (ctx, aggregator)) => createResults(key, aggregator, ctx)
      }.toIterator
    }
  }

  def planDescriptionWithoutCardinality = source.planDescription.
                        andThen(this.id, "EagerAggregation", variables, Arguments.KeyNames(keyExpressions.toSeq))

  def dup(sources: List[Pipe]): Pipe = {
    val (source :: Nil) = sources
    copy(source = source)(estimatedCardinality)
  }

  def withEstimatedCardinality(estimated: Double) = copy()(Some(estimated))
}
