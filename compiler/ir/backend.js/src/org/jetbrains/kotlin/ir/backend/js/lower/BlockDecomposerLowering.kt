/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FunctionLoweringPass
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.descriptors.JsSymbolBuilder
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.types.KotlinType
import kotlin.math.max

class BlockDecomposerLowering(val context: JsIrBackendContext) : FunctionLoweringPass {

    private lateinit var function: IrFunction
    private var tmpVarCounter: Int = 0

    private val statementVisitor = StatementVisitor()
    private val expressionVisitor = ExpressionVisitor()

    override fun lower(irFunction: IrFunction) {
        function = irFunction
        tmpVarCounter = 0
        irFunction.body?.accept(statementVisitor, VisitData())
    }

    enum class VisitStatus {
        DECOMPOSED,
        TERMINATED,
        KEPT
    }


    abstract class VisitResult(val status: VisitStatus) {
        open val statements: MutableList<IrStatement> get() = error("")
        open val resultValue: IrGetValue get() = error("")

        abstract fun process(action: VisitResult.() -> VisitResult): VisitResult
        abstract fun <T> evaluate(default: T, action: VisitResult.(d: T) -> T): T
        abstract fun execute(action: VisitResult.() -> Unit): VisitResult

    }

    object KeptResult : VisitResult(VisitStatus.KEPT) {
        override fun process(action: VisitResult.() -> VisitResult) = this
        override fun <T> evaluate(default: T, action: VisitResult.(d: T) -> T) = default
        override fun execute(action: VisitResult.() -> Unit) = this
    }

    abstract class ChangedResult(
        override val statements: MutableList<IrStatement>,
        val resultVariable: IrVariableSymbol?,
        status: VisitStatus
    ) :
        VisitResult(status) {
        override val resultValue get() = JsIrBuilder.buildGetValue(resultVariable!!)

        override fun process(action: VisitResult.() -> VisitResult) = this.action()
        override fun <T> evaluate(default: T, action: VisitResult.(d: T) -> T) = this.action(default)
        override fun execute(action: VisitResult.() -> Unit) = apply { action() }
    }

    class DecomposedResult(statements: MutableList<IrStatement>, resultVariable: IrVariableSymbol? = null) :
        ChangedResult(statements, resultVariable, VisitStatus.DECOMPOSED) {

        constructor(statement: IrStatement, resultVariable: IrVariableSymbol? = null) : this(mutableListOf(statement), resultVariable)
    }

    class TerminatedResult(statements: MutableList<IrStatement>, resultVariable: IrVariableSymbol? = null) :
        ChangedResult(statements, resultVariable, VisitStatus.TERMINATED)

    class VisitData

    abstract inner class DecomposerVisitor : IrElementVisitor<VisitResult, VisitData> {
        override fun visitElement(element: IrElement, data: VisitData) = KeptResult
    }

    inner class StatementVisitor : DecomposerVisitor() {
        override fun visitBlockBody(body: IrBlockBody, data: VisitData): VisitResult {
            body.statements.transformFlat {
                processStatement(it, data)
            }

            return KeptResult
        }

        override fun visitContainerExpression(expression: IrContainerExpression, data: VisitData): VisitResult {
            expression.statements.transformFlat {
                processStatement(it, data)
            }

            return KeptResult
        }

        private fun processStatement(statement: IrStatement, data: VisitData): List<IrStatement>? {
            val result = statement.accept(this, data)

            if (result == KeptResult) return null
            return result.statements
        }

        override fun visitExpression(expression: IrExpression, data: VisitData): VisitResult = expression.accept(expressionVisitor, data)

        override fun visitReturn(expression: IrReturn, data: VisitData): VisitResult {
            val expressionResult = expression.value.accept(expressionVisitor, data)

            return expressionResult.process {
                val returnValue = expressionResult.resultValue
                expressionResult.statements += IrReturnImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.returnTargetSymbol,
                    returnValue
                )
                DecomposedResult(expressionResult.statements)
            }
        }

        override fun visitBreakContinue(jump: IrBreakContinue, data: VisitData) = KeptResult as VisitResult

        override fun visitThrow(expression: IrThrow, data: VisitData): VisitResult {
            val expressionResult = expression.value.accept(expressionVisitor, data)

            return expressionResult.process {
                val returnValue = expressionResult.resultValue
                expressionResult.statements += IrThrowImpl(expression.startOffset, expression.endOffset, expression.type, returnValue)
                DecomposedResult(expressionResult.statements)
            }
        }

        override fun visitVariable(declaration: IrVariable, data: VisitData): VisitResult {
            declaration.initializer?.let {
                val initResult = it.accept(expressionVisitor, data)
                return initResult.process {
                    statements += declaration.apply { initializer = resultValue }
                    DecomposedResult(statements)
                }
            }

            return KeptResult
        }

        override fun visitWhen(expression: IrWhen, data: VisitData): VisitResult {
            val newWhen = processWhen(expression, data, expressionVisitor, this) { visitResult, original ->
                visitResult.evaluate(listOf(original)) { statements }
            }

            if (newWhen != expression) {
                return DecomposedResult(newWhen)
            }
            return KeptResult
        }

        private inner class ConditionSplitter(val varDeclarations: MutableList<IrStatement>) : IrElementTransformerVoid() {
            fun run(statements: MutableList<IrStatement>) {
                statements.transformFlat {
                    lowerStatements(it)
                }
            }

            override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
                expression.statements.transformFlat {
                    lowerStatements(it)
                }
                return expression
            }

            private fun lowerStatements(statement: IrStatement): List<IrStatement>? = if (statement is IrVariable) {
                val varSymbol = statement.symbol
                val returnList = mutableListOf<IrStatement>()
                statement.initializer?.let {
                    returnList += JsIrBuilder.buildSetVariable(varSymbol, it)
                }
                varDeclarations.add(statement.apply { initializer = null })
                returnList
            } else {
                statement.transform(this, null)
                null
            }
        }

        private inner class BreakContinueUpdater(val breakLoop: IrLoop, val continueLoop: IrLoop) : IrElementTransformer<IrLoop> {
            override fun visitBreak(jump: IrBreak, data: IrLoop) = jump.apply {
                if (loop == data) loop = breakLoop
            }

            override fun visitContinue(jump: IrContinue, data: IrLoop) = jump.apply {
                if (loop == data) loop = continueLoop
            }
        }

        // while (c_block {}) {
        //  body {}
        // }
        //
        // is transformed into
        //
        // c_block_vars { var tmp1; ...; var tmpn }
        // while (true) {
        //   var cond = c_block {}
        //   if (!cond) break
        //   body {}
        // }
        //
        override fun visitWhileLoop(loop: IrWhileLoop, data: VisitData): VisitResult {

            val conditionResult = loop.condition.accept(expressionVisitor, data)
            val bodyResult = loop.body?.accept(this, data)
            val unitType = context.builtIns.unitType

            return conditionResult.process {
                val statements = conditionResult.statements
                val variables = mutableListOf<IrStatement>()

                ConditionSplitter(variables).run(statements)

                bodyResult?.run { assert(status == VisitStatus.KEPT) }

                val condVariable = conditionResult.resultValue
                val thenBlock = JsIrBuilder.buildBlock(unitType, listOf(JsIrBuilder.buildBreak(unitType, loop)))
                val breakCond = JsIrBuilder.buildCall(context.irBuiltIns.booleanNotSymbol).apply {
                    putValueArgument(0, condVariable)
                }

                statements.add(JsIrBuilder.buildIfElse(unitType, breakCond, thenBlock))

                val oldBody = loop.body!!

                val newBody = statements + oldBody

                val newLoop = IrWhileLoopImpl(loop.startOffset, loop.endOffset, loop.type, loop.origin).apply {
                    label = loop.label
                    condition = constTrue
                    body = oldBody.run { IrBlockImpl(startOffset, endOffset, type, origin, newBody) }
                }

                newLoop.body?.transform(BreakContinueUpdater(newLoop, newLoop), loop)

                DecomposedResult(variables).also { it.statements += newLoop }
            }
        }

        // do  {
        //  body {}
        // } while (c_block {})
        //
        // is transformed into
        //
        // c_block_vars { var tmp1; ...; var tmpn; var cond; }
        // do {
        //   do {
        //     body {}
        //   } while (false)
        //   cond = c_block {}
        // } while (cond)
        //
        override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: VisitData): VisitResult {

            val bodyResult = loop.body?.accept(this, data)
            val conditionResult = loop.condition.accept(expressionVisitor, data)
            val unitType = context.builtIns.unitType

            return conditionResult.process {
                val statements = conditionResult.statements
                val variables = mutableListOf<IrStatement>()

                ConditionSplitter(variables).run(statements)

                bodyResult?.run { assert(status == VisitStatus.KEPT) }

                val body = loop.body!!

                val innerLoop = IrDoWhileLoopImpl(loop.startOffset, loop.endOffset, unitType, loop.origin, body, constFalse).apply {
                    label = makeLoopLabel()
                }

                val condVariable = conditionResult.resultValue
                val newBody = JsIrBuilder.buildBlock(body.type, listOf(innerLoop) + statements)
                val newLoop = IrDoWhileLoopImpl(loop.startOffset, loop.endOffset, unitType, loop.origin, newBody, condVariable).apply {
                    label = loop.label ?: makeLoopLabel()
                }

                body.transform(BreakContinueUpdater(newLoop, innerLoop), loop)

                DecomposedResult(variables).also { it.statements += newLoop }
            }
        }

        override fun visitTypeOperator(expression: IrTypeOperatorCall, data: VisitData): VisitResult {
            val argumentResult = expression.argument.accept(expressionVisitor, data)

            return argumentResult.process {
                val newOperator = IrTypeOperatorCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    expression.operator,
                    expression.typeOperand,
                    resultValue,
                    expression.typeOperandClassifier
                )

                val resVar = if (!KotlinBuiltIns.isUnit(expression.type)) makeTempVar(expression.type) else null

                DecomposedResult(statements, resVar).apply {
                    statements += resultVariable?.let { JsIrBuilder.buildVar(it).apply { initializer = newOperator } } ?: newOperator
                }
            }
        }

        override fun visitField(declaration: IrField, data: VisitData): VisitResult {
            if (declaration.initializer == null) return KeptResult

            val result = declaration.initializer!!.accept(expressionVisitor, data)

            return result.process {
                TODO()
            }
        }

        override fun visitSetField(expression: IrSetField, data: VisitData): VisitResult {
            var needNew = false

            val receiverResult = expression.receiver?.accept(expressionVisitor, data)
            val valueResult = expression.value.accept(expressionVisitor, data)

            val newStatements = mutableListOf<IrStatement>()

            val newReceiver = receiverResult?.evaluate(expression.receiver) {
                newStatements += statements
                needNew = true
                resultValue
            }

            val newValue = valueResult.evaluate(expression.value) {
                newStatements += statements
                needNew = true
                resultValue
            }

            if (needNew) {
                newStatements += JsIrBuilder.buildSetField(expression.symbol, newReceiver, newValue, expression.superQualifierSymbol)
                return DecomposedResult(newStatements)
            }

            return KeptResult
        }

        override fun visitSetVariable(expression: IrSetVariable, data: VisitData): VisitResult {
            val valueResult = expression.value.accept(expressionVisitor, data)

            return valueResult.process {
                statements += JsIrBuilder.buildSetVariable(expression.symbol, resultValue)
                DecomposedResult(statements)
            }
        }
    }

    inner class ExpressionVisitor : DecomposerVisitor() {
        override fun visitExpression(expression: IrExpression, data: VisitData) = KeptResult

        override fun visitContainerExpression(expression: IrContainerExpression, data: VisitData): VisitResult {
            //
            // val x = block {
            //   ...
            //   expr
            // }
            //
            // is transformed into
            //
            // val x_tmp
            // block {
            //   ...
            //   x_tmp = expr
            // }
            // val x = x_tmp

            val variable = makeTempVar(expression.type)

            val stmtVisitor = statementVisitor
            val exprVisitor = expressionVisitor

            val varDeclaration = JsIrBuilder.buildVar(variable)

            val blockStatements = expression.statements

            val lastStatement: IrStatement? = blockStatements.lastOrNull()

            val body = when (expression) {
                is IrBlock -> IrBlockImpl(
                    expression.startOffset,
                    expression.endOffset,
                    context.builtIns.unitType,
                    expression.origin
                )
                is IrComposite -> IrCompositeImpl(
                    expression.startOffset,
                    expression.endOffset,
                    context.builtIns.unitType,
                    expression.origin
                )
                is IrReturnableBlock -> TODO("IrReturnableBlock")
                else -> error("Unsupported block type")
            }

            val collectingList = body.statements

            blockStatements.asSequence().take(max(blockStatements.size - 1, 0)).forEach {
                val tempResult = it.accept(stmtVisitor, data)
                collectingList += tempResult.evaluate(listOf(it)) { statements }
            }

            if (lastStatement != null) {
                val result = lastStatement.accept(exprVisitor, data).evaluate(lastStatement as IrExpression) {
                    collectingList += statements
                    resultValue
                }
                collectingList += JsIrBuilder.buildSetVariable(variable, result)
            } else {
                // do not allow variable to be uninitialized
                varDeclaration.initializer = JsIrBuilder.buildNull(expression.type)
            }


            return DecomposedResult(mutableListOf(varDeclaration, body), variable)
        }

        override fun visitGetField(expression: IrGetField, data: VisitData): VisitResult {
            val res = super.visitGetField(expression, data)

            return res.process {
                val newExpression = JsIrBuilder.buildGetField(expression.symbol, resultValue, expression.superQualifierSymbol)
                DecomposedResult(newExpression)
            }
        }

        private fun prepareArgument(arg: IrExpression, needWrap: Boolean, statements: MutableList<IrStatement>): IrExpression {
            return if (needWrap) {
                val wrapVar = makeTempVar(arg.type)
                statements += JsIrBuilder.buildVar(wrapVar).apply { initializer = arg }
                JsIrBuilder.buildGetValue(wrapVar)
            } else arg
        }

        private fun mapArguments(
            argResults: List<Pair<IrExpression?, VisitResult?>>,
            toDecompose: Int,
            newStatements: MutableList<IrStatement>
        ): List<IrExpression?> {
            var decomposed = 0
            return argResults.map {
                val original = it.first
                val result = it.second
                val needWrap = decomposed < toDecompose
                original?.let {
                    val evaluated = result!!.evaluate(it) {
                        newStatements += statements
                        resultValue.apply { decomposed++ }
                    }
                    prepareArgument(evaluated, needWrap, newStatements)
                }
            }
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression, data: VisitData): VisitResult {
            // The point here is to keep original evaluation order so (there is the same story for StringConcat)
            // d.foo(p1, p2, block {}, p4, block {}, p6, p7)
            //
            // is transformed into
            //
            // var d_tmp = d
            // var p1_tmp = p1
            // var p2_tmp = p2
            // var p3_rmp = block {}
            // var p4_tmp = p4
            // var p5_tmp = block {}
            // d_tmp.foo(p1_tmp, p2_tmp, p3_tmp, p4_tmp, p5_tmp, p6, p7)
            var argsCounter = 0

            val dispatchResult = expression.dispatchReceiver?.accept(this, data)?.execute { argsCounter++ }
            val extensionResult = expression.extensionReceiver?.accept(this, data)?.execute { argsCounter++ }

            val argumentResults = mutableListOf<Pair<IrExpression?, VisitResult?>>().also {
                for (i in 0 until expression.valueArgumentsCount) {
                    val arg = expression.getValueArgument(i)
                    val result = arg?.accept(this, data)?.execute { argsCounter++ }
                    it += Pair(arg, result)
                }
            }

            if (argsCounter == 0) return KeptResult

            val newStatements = mutableListOf<IrStatement>()

            val needWrapDR = 0 != argsCounter
            val newDispatchReceiverValue = dispatchResult?.evaluate(expression.dispatchReceiver) {
                newStatements += statements
                resultValue.apply { argsCounter-- }
            }
            val newDispatchReceiver = newDispatchReceiverValue?.let { prepareArgument(it, needWrapDR, newStatements) }

            val needWrapER = 0 != argsCounter
            val newExtensionReceiverValue = extensionResult?.evaluate(expression.extensionReceiver) {
                newStatements += statements
                resultValue.apply { argsCounter-- }
            }
            val newExtensionReceiver = newExtensionReceiverValue?.let { prepareArgument(it, needWrapER, newStatements) }

            val newArguments = mapArguments(argumentResults, argsCounter, newStatements)

            expression.dispatchReceiver = newDispatchReceiver
            expression.extensionReceiver = newExtensionReceiver

            newArguments.forEachIndexed { i, v -> expression.putValueArgument(i, v) }

            val resultVar = makeTempVar(expression.type)

            newStatements += JsIrBuilder.buildVar(resultVar).apply { initializer = expression }

            return DecomposedResult(newStatements, resultVar)
        }

        override fun visitWhen(expression: IrWhen, data: VisitData): VisitResult {
            val collectiveVar = makeTempVar(expression.type)
            val varDeclaration = JsIrBuilder.buildVar(collectiveVar)
            val newWhen = processWhen(expression, data, this, this) { visitResult, original ->
                val resultList = mutableListOf<IrStatement>()
                val newResult = visitResult.evaluate(original as IrExpression) {
                    resultValue.apply { resultList += statements }
                }
                resultList.apply { add(JsIrBuilder.buildSetVariable(collectiveVar, newResult)) }
            }

            return DecomposedResult(mutableListOf(varDeclaration, newWhen), collectiveVar)
        }

        override fun visitStringConcatenation(expression: IrStringConcatenation, data: VisitData): VisitResult {
            var decomposed = 0

            val arguments = expression.arguments.map {
                Pair(it, it.accept(this, data).execute { decomposed++ })
            }

            if (decomposed == 0) return KeptResult

            val newStatements = mutableListOf<IrStatement>()
            val newArguments = mapArguments(arguments, decomposed, newStatements)
            val newExpression =
                IrStringConcatenationImpl(expression.startOffset, expression.endOffset, expression.type, newArguments as List<IrExpression>)

            val resultVar = makeTempVar(expression.type)
            newStatements += JsIrBuilder.buildVar(resultVar).apply { initializer = newExpression }

            return DecomposedResult(newStatements, resultVar)
        }

        private fun <T : IrStatement> transformTermination(
            typeGetter: () -> KotlinType,
            valueGetter: () -> IrExpression,
            instantiater: (value: IrExpression) -> T,
            data: VisitData
        ): TerminatedResult {
            val type = typeGetter()
            val value = valueGetter()
            val valueResult = value.accept(this, data)
            val newStatements = mutableListOf<IrStatement>()
            val aggregateVar = makeTempVar(type)

            val returnValue = valueResult.evaluate(value) {
                resultValue.apply { newStatements += statements }
            }

            newStatements += instantiater(returnValue)
            newStatements += JsIrBuilder.buildVar(aggregateVar).apply { initializer = JsIrBuilder.buildNull(type) }

            return TerminatedResult(newStatements, aggregateVar)
        }


        override fun visitReturn(expression: IrReturn, data: VisitData) = transformTermination(
            { expression.type },
            { expression.value },
            { v -> IrReturnImpl(expression.startOffset, expression.endOffset, expression.type, expression.returnTargetSymbol, v) },
            data
        )

        override fun visitThrow(expression: IrThrow, data: VisitData) = transformTermination(
            { expression.type },
            { expression.value },
            { v -> IrThrowImpl(expression.startOffset, expression.endOffset, expression.type, v) },
            data
        )

        override fun visitBreakContinue(jump: IrBreakContinue, data: VisitData): VisitResult {
            val aggregateVar = makeTempVar(jump.type)
            val irVarDeclaration = JsIrBuilder.buildVar(aggregateVar).apply { initializer = JsIrBuilder.buildNull(jump.type) }
            return DecomposedResult(mutableListOf(jump, irVarDeclaration), aggregateVar)
        }

    }

    val constTrue = JsIrBuilder.buildBoolean(context.builtIns.booleanType, true)
    val constFalse = JsIrBuilder.buildBoolean(context.builtIns.booleanType, false)

    fun makeTempVar(type: KotlinType) =
        JsSymbolBuilder.buildTempVar(function.symbol, type, "tmp\$dcms\$${tmpVarCounter++}", true)

    fun makeLoopLabel() = "\$l\$${tmpVarCounter++}"

    private fun processWhen(
        expression: IrWhen,
        data: VisitData,
        expressionVisitor: DecomposerVisitor,
        statementVisitor: DecomposerVisitor,
        bodyBuilder: (VisitResult, IrStatement) -> List<IrStatement>
    ): IrStatement {
        var needNewConds = false
        var needNewBodies = false

        val branches = expression.branches.map {
            val conditionResult = it.condition.accept(expressionVisitor, data).execute { needNewConds = true }
            val bodyResult = it.result.accept(statementVisitor, data).execute { needNewBodies = true }
            Pair(conditionResult, bodyResult)
        }

        // keep it as Is
        if (expressionVisitor != statementVisitor && !(needNewConds || needNewBodies)) return expression

        val unitType = context.builtIns.unitType

        if (needNewConds) {

            // [val x = ] when {
            //  c1_block {} -> b1_block {}
            //  ....
            //  cn_block {} -> bn_block {}
            //  else -> else_block {}
            // }
            //
            // transformed into if-else chain
            // [var x_tmp]
            // val c1 = c1_block {}
            // if (c1) {
            //   [x_tmp =] b1_block {}
            // } else {
            //   val c2 = c2_block {}
            //   if (c2) {
            //     [x_tmp =] b2_block{}
            //   } else {
            //         ...
            //           else {
            //              [x_tmp =] else_block {}
            //           }
            // }
            // [val x = x_tmp]
            //
            val block = IrBlockImpl(expression.startOffset, expression.endOffset, unitType, expression.origin)

            branches.foldIndexed(block) { i, a, b ->
                val originalBranch = expression.branches[i]
                val originalCondition = originalBranch.condition
                val originalResult = originalBranch.result
                val condResult = b.first
                val resResult = b.second

                val irCondition = condResult.evaluate(originalCondition) {
                    resultValue.apply { a.statements += statements }
                }

                val thenBlock = IrBlockImpl(originalResult.startOffset, originalResult.endOffset, unitType).apply {
                    statements += bodyBuilder(resResult, originalResult)
                }

                JsIrBuilder.buildBlock(unitType).also {
                    val elseBlock = if (originalBranch is IrElseBranch) null else it

                    val ifElseNode = IrIfThenElseImpl(
                        originalBranch.startOffset,
                        originalBranch.endOffset,
                        unitType,
                        irCondition,
                        thenBlock,
                        elseBlock,
                        expression.origin
                    )
                    a.statements += ifElseNode
                }
            }

            return block
        }

        // [val x = ] when {
        //  c1 -> b1_block {}
        //  ....
        //  cn -> bn_block {}
        //  else -> else_block {}
        // }
        //
        // transformed into if-else chain
        //
        // [var x_tmp]
        // when {
        //   c1 -> [x_tmp =] b1_block {}
        //   ...
        //   cn -> [x_tmp =] bn_block {}
        //   else -> [x_tmp =] else_block {}
        // }
        // [val x = x_tmp]

        val newWhen = IrWhenImpl(expression.startOffset, expression.endOffset, unitType, expression.origin)

        branches.forEachIndexed { i, b ->
            val originalBranch = expression.branches[i]
            val condition = originalBranch.condition
            val originalResult = originalBranch.result
            val resResult = b.second

            val body = IrBlockImpl(originalResult.startOffset, originalResult.endOffset, unitType).apply {
                statements += bodyBuilder(resResult, originalResult)
            }

            newWhen.branches += when (originalBranch) {
                is IrElseBranch -> IrElseBranchImpl(originalBranch.startOffset, originalBranch.endOffset, condition, body)
                else /* IrBranch */ -> IrBranchImpl(originalBranch.startOffset, originalBranch.endOffset, condition, body)
            }
        }

        return newWhen
    }
}