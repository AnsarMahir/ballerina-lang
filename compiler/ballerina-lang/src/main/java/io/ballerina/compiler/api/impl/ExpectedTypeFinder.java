/*
 * Copyright (c) 2022, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.compiler.api.impl;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.TypeBuilder;
import io.ballerina.compiler.api.Types;
import io.ballerina.compiler.api.impl.symbols.BallerinaArrayTypeSymbol;
import io.ballerina.compiler.api.impl.symbols.TypesFactory;
import io.ballerina.compiler.api.impl.util.SymbolUtils;
import io.ballerina.compiler.api.symbols.AnnotationSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.IntersectionTypeSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.StringTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.AssignmentStatementNode;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.BinaryExpressionNode;
import io.ballerina.compiler.syntax.tree.CaptureBindingPatternNode;
import io.ballerina.compiler.syntax.tree.DefaultableParameterNode;
import io.ballerina.compiler.syntax.tree.ErrorConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.ExplicitAnonymousFunctionExpressionNode;
import io.ballerina.compiler.syntax.tree.ExplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionFunctionBodyNode;
import io.ballerina.compiler.syntax.tree.FieldAccessExpressionNode;
import io.ballerina.compiler.syntax.tree.FromClauseNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.IfElseStatementNode;
import io.ballerina.compiler.syntax.tree.ImplicitAnonymousFunctionExpressionNode;
import io.ballerina.compiler.syntax.tree.ImplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.IndexedExpressionNode;
import io.ballerina.compiler.syntax.tree.LetVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.ListConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.ListenerDeclarationNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingMatchPatternNode;
import io.ballerina.compiler.syntax.tree.MatchClauseNode;
import io.ballerina.compiler.syntax.tree.MatchStatementNode;
import io.ballerina.compiler.syntax.tree.MethodCallExpressionNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeTransformer;
import io.ballerina.compiler.syntax.tree.ObjectFieldNode;
import io.ballerina.compiler.syntax.tree.ParenthesizedArgList;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.RecordFieldWithDefaultValueNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.ReturnTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SelectClauseNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TableConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.VariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.WaitActionNode;
import io.ballerina.compiler.syntax.tree.WhileStatementNode;
import io.ballerina.projects.Document;
import io.ballerina.tools.text.LinePosition;
import org.ballerinalang.model.symbols.SymbolKind;
import org.ballerinalang.model.types.TypeKind;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BInvokableSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BVarSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.types.BArrayType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BErrorType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BMapType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BStreamType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BTableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BTypeReferenceType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BXMLType;
import org.wso2.ballerinalang.compiler.tree.BLangCompilationUnit;
import org.wso2.ballerinalang.compiler.tree.BLangFunction;
import org.wso2.ballerinalang.compiler.tree.BLangNode;
import org.wso2.ballerinalang.compiler.tree.BLangSimpleVariable;
import org.wso2.ballerinalang.compiler.tree.clauses.BLangSelectClause;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangArrowFunction;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangBinaryExpr;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangErrorConstructorExpr;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangExpression;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangFieldBasedAccess;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangIndexBasedAccess;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangInvocation;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangListConstructorExpr;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangNamedArgsExpression;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangNumericLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangRecordLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangSimpleVarRef;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangTableConstructorExpr;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangTypeInit;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangWaitExpr;
import org.wso2.ballerinalang.compiler.tree.statements.BLangIf;
import org.wso2.ballerinalang.compiler.tree.statements.BLangWhile;
import org.wso2.ballerinalang.compiler.tree.types.BLangUserDefinedType;
import org.wso2.ballerinalang.compiler.util.CompilerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.ballerina.compiler.api.impl.PositionUtil.isPosWithinRange;
import static io.ballerina.compiler.api.impl.PositionUtil.isWithinParenthesis;
import static io.ballerina.compiler.api.symbols.SymbolKind.MODULE;
import static org.ballerinalang.model.tree.NodeKind.ERROR_CONSTRUCTOR_EXPRESSION;

/**
 * This transformer is used to resolve an expected type for a given context.
 *
 * @since 2201.4.0
 */
public class ExpectedTypeFinder extends NodeTransformer<Optional<TypeSymbol>> {

    private final SemanticModel semanticModel;
    private final BLangCompilationUnit bLangCompilationUnit;
    private final List<Node> visitedNodes = new ArrayList<>();
    private final TypesFactory typesFactory;
    private final LinePosition linePosition;

    private final NodeFinder nodeFinder;
    private final Document document;

    public  ExpectedTypeFinder(SemanticModel semanticModel, BLangCompilationUnit bLangCompilationUnit,
                               CompilerContext context, LinePosition linePosition, Document srcDocument) {
        this.nodeFinder = new NodeFinder(true);
        this.semanticModel = semanticModel;
        this.bLangCompilationUnit = bLangCompilationUnit;
        this.typesFactory = TypesFactory.getInstance(context);
        this.linePosition = linePosition;
        this.document = srcDocument;
    }

    @Override
    public Optional<TypeSymbol> transform(SimpleNameReferenceNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        // TODO remove this after fix #38522
        if (bLangNode == null) {
            Optional<Symbol> symbol = this.getSymbolByName(node.name().text());
            if (symbol.isEmpty()) {
                return Optional.empty();
            }

            return SymbolUtils.getTypeDescriptor(symbol.get());
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(AnnotationNode node) {
        Node annotationRef = node.annotReference();
        Optional<Symbol> annotationSymbol;
        Predicate<Symbol> predicate = symbol -> symbol.getName().isPresent() &&
                symbol.kind() == io.ballerina.compiler.api.symbols.SymbolKind.ANNOTATION;

        if (annotationRef.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            QualifiedNameReferenceNode qNameRef = (QualifiedNameReferenceNode) annotationRef;
            Predicate<Symbol> qNamePredicate =
                    predicate.and(symbol -> symbol.getName().get().equals(qNameRef.identifier().text()));
            annotationSymbol = this.getTypeFromQNameReference(qNameRef, qNamePredicate);
        } else if (annotationRef.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE) {
            annotationSymbol = this.getSymbolByName(((SimpleNameReferenceNode) annotationRef).name().text(), predicate);
        } else {
            return Optional.empty();
        }

        if (annotationSymbol.isEmpty()) {
            return Optional.empty();
        }

        return ((AnnotationSymbol) annotationSymbol.get()).typeDescriptor();
    }

    @Override
    public Optional<TypeSymbol> transform(SpecificFieldNode node) {
        Optional<TypeSymbol> elementType = Optional.empty();
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangRecordLiteral.BLangRecordVarNameField &&
                ((BLangRecordLiteral.BLangRecordVarNameField) bLangNode).symbol != null) {
            if (node.parent().kind() == SyntaxKind.MAPPING_CONSTRUCTOR) {
                BLangExpression bLangExpression = ((BLangRecordLiteral.BLangRecordVarNameField) bLangNode)
                        .impConversionExpr;
                if (bLangExpression != null && bLangExpression.getBType().getKind() == TypeKind.ANY) {
                        BType bType = bLangExpression.getBType();
                        return getTypeFromBType(bType);
                    }
                }
            }

        if (bLangNode instanceof BLangRecordLiteral.BLangRecordVarNameField) {
            elementType = getTypeFromBType(((BLangRecordLiteral.BLangRecordVarNameField) bLangNode).symbol.getType());
        }

        if (elementType.isPresent()) {
            return elementType;
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(ObjectFieldNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(BasicLiteralNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null || bLangNode.toString().equals("")) {
            return Optional.empty();
        }

        return getTypeFromBType(bLangNode.getBType());
    }

    @Override
    public Optional<TypeSymbol> transform(AssignmentStatementNode node) {
        return this.visit(node.varRef());
    }

    @Override
    public Optional<TypeSymbol> transform(FieldAccessExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(LetVariableDeclarationNode node) {
        return this.visit(node.typedBindingPattern().bindingPattern());
    }

    @Override
    public Optional<TypeSymbol> transform(ListenerDeclarationNode node) {
        Optional<TypeDescriptorNode> typeDesc = node.typeDescriptor();
        if (typeDesc.isEmpty()) {
            return Optional.empty();
        }

        // check for a possibility to find a recursive case
        return typeDesc.get().apply(this);
    }

    @Override
    public Optional<TypeSymbol> transform(ModuleVariableDeclarationNode node) {
        return this.visit(node.typedBindingPattern().bindingPattern());
    }

    @Override
    public Optional<TypeSymbol> transform(VariableDeclarationNode node) {
        if (node.typedBindingPattern().typeDescriptor().kind() == SyntaxKind.VAR_TYPE_DESC) {
            throw new IllegalStateException();
        }

        return this.visit(node.typedBindingPattern().bindingPattern());
    }

    @Override
    public Optional<TypeSymbol> transform(BinaryExpressionNode node) {
        if (!node.operator().isMissing() && node.operator().text().equals("*") || node.operator().text().equals("/")) {
            return Optional.empty();
        }

        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (!(bLangNode instanceof BLangBinaryExpr)) {
            return Optional.empty();
        }

        BLangBinaryExpr bLangBinaryExpr = (BLangBinaryExpr) bLangNode;
        if (bLangBinaryExpr.rhsExpr.expectedType.getKind() == TypeKind.OTHER &&
                bLangBinaryExpr.lhsExpr.expectedType.getKind() == TypeKind.OTHER) {
            return Optional.empty();
        } else if (!(bLangBinaryExpr.rhsExpr.expectedType.getKind() == TypeKind.OTHER)) {
            return getExpectedType(bLangBinaryExpr.rhsExpr);

        } else if (!(bLangBinaryExpr.lhsExpr.expectedType.getKind() == TypeKind.OTHER)) {
            return getExpectedType(bLangBinaryExpr.lhsExpr);
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(FunctionCallExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangInvocation) {
            // check position is in parameter context
            if (isWithinParenthesis(linePosition, node.openParenToken(), node.closeParenToken())) {
                int size = ((BLangInvocation) bLangNode).argExprs.size();
                if (size == 0) {
                    return getParamType(bLangNode);
                } else {
                    int argumentIndex = 0;
                    for (BLangNode nodeInst : ((BLangInvocation) bLangNode).argExprs) {
                        // offset is only considered
                        if (nodeInst.getPosition().lineRange().endLine().offset() < linePosition.offset()) {
                            argumentIndex += 1;
                        }
                    }

                    if ((((BLangInvocation) bLangNode).argExprs.get(argumentIndex).expectedType == null)) {
                        return Optional.empty();
                    }

                    return getExpectedType(((BLangInvocation) bLangNode).argExprs.get(argumentIndex));
                }
            }

            return getExpectedType(bLangNode);
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(MethodCallExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangInvocation) {
            if (isWithinParenthesis(linePosition, node.openParenToken(), node.closeParenToken())) {
                int size = ((BLangInvocation) bLangNode).argExprs.size();
                boolean langLibInvocation = ((BLangInvocation) bLangNode).langLibInvocation;
                if (size == 0 && !langLibInvocation) {
                    return getParamType(bLangNode);
                } else {
                    int argumentIndex = 0;
                    for (BLangNode nodeInst : ((BLangInvocation) bLangNode).argExprs) {
                        // offset is only considered
                        if (nodeInst.getPosition().lineRange().endLine().offset() < linePosition.offset()) {
                            argumentIndex += 1;
                        }
                    }

                    if (langLibInvocation) {
                        if (((BLangInvocation) bLangNode).expr.getBType().getKind() == TypeKind.ARRAY) {
                            return Optional.ofNullable(typesFactory.getTypeDescriptor
                                    (((BArrayType) ((BLangInvocation) bLangNode).expr.expectedType).eType));
                        }

                        if (size == 1) {
                            return getParamType(bLangNode);
                        }

                        return getExpectedType(((BLangInvocation) bLangNode).argExprs.get(argumentIndex - 1));
                    }

                    if ((((BLangInvocation) bLangNode).argExprs.get(argumentIndex).expectedType == null)) {
                        return Optional.empty();
                    }

                    return getExpectedType(((BLangInvocation) bLangNode).argExprs.get(argumentIndex));
                }
            }

            return getExpectedType(bLangNode);
        }

        return Optional.empty();
    }

    public Optional<TypeSymbol> transform(QualifiedNameReferenceNode node) {
        // Added to prevent from the case module1:$missingNode returns a expectedType ("string")
        if (node.identifier().isMissing()) {
            return Optional.empty();
        }

        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangSimpleVarRef) {
            if (((BLangSimpleVarRef) bLangNode).symbol != null) {
                return getTypeFromBType(((BLangSimpleVarRef) bLangNode).symbol.getType());
            }
        }

        if (bLangNode instanceof BLangUserDefinedType) {
            if (((BLangUserDefinedType) bLangNode).symbol != null) {
                return getTypeFromBType(((BLangUserDefinedType) bLangNode).symbol.getType());
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(ExplicitAnonymousFunctionExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangFunction) {
            return Optional.of(typesFactory.getTypeDescriptor(((BLangFunction) nodeFinder.lookup
                    (this.bLangCompilationUnit, node.lineRange())).returnTypeNode.getBType()));
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(ImplicitAnonymousFunctionExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (!(bLangNode instanceof BLangArrowFunction)) {
            return Optional.empty();
        }

        BType returnType = ((BLangArrowFunction) nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange()))
                .funcType.getReturnType();
        if (!node.rightDoubleArrow().isMissing() &&
               node.rightDoubleArrow().lineRange().endLine().offset() <= linePosition.offset()) {
            // Cursor is at the expression node
            return Optional.of(typesFactory.getTypeDescriptor(returnType));
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(PositionalArgumentNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangRecordLiteral) {
            return getExpectedType(bLangNode);
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(FunctionDefinitionNode node) {
        Optional<ReturnTypeDescriptorNode> returnTypeDesc = node.functionSignature().returnTypeDesc();
        if (returnTypeDesc.isEmpty()) {
            return Optional.empty();
        }

        Optional<Symbol> functionSymbol = this.semanticModel.symbol(node);

        if (functionSymbol.isEmpty()) {
            return Optional.empty();
        }

        return ((FunctionSymbol) functionSymbol.get()).typeDescriptor().returnTypeDescriptor();
    }

    public Optional<TypeSymbol> transform(MatchClauseNode matchClauseNode) {
        return matchClauseNode.parent().apply(this);
    }

    public Optional<TypeSymbol> transform(MatchStatementNode matchStatementNode) {
        return this.semanticModel.typeOf(matchStatementNode.condition());
    }

    @Override
    public Optional<TypeSymbol> transform(MappingMatchPatternNode mappingMatchPatternNode) {
        return mappingMatchPatternNode.parent().apply(this);
    }

    @Override
    public Optional<TypeSymbol> transform(ExplicitNewExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        ParenthesizedArgList parenthesizedArgList = node.parenthesizedArgList();
        // check its is parameter context
        if (isWithinParenthesis(linePosition, parenthesizedArgList.openParenToken(),
                parenthesizedArgList.closeParenToken())) {
            if (bLangNode instanceof BLangTypeInit) {
                List<BVarSymbol> params = ((BInvokableSymbol) (((BLangInvocation) ((BLangTypeInit) bLangNode).
                        initInvocation).symbol)).params;
                int argIndex = 0;
                List<BLangExpression> argsExpr = ((BLangTypeInit) bLangNode).argsExpr;
                if (argsExpr.size() == 0 && params.size() > 0) {
                    return getTypeFromBType(params.get(0).getType());
                }

                for (BLangExpression bLangExpression : argsExpr) {
                    if (isPosWithinRange(linePosition, bLangExpression.getPosition().lineRange())) {
                        if (argIndex < params.size()) {
                            return getTypeFromBType(params.get(argIndex).getType());
                        }

                        break;
                    }

                    argIndex += 1;
                }
            }
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(ImplicitNewExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        Optional<ParenthesizedArgList> parenthesizedArgList = node.parenthesizedArgList();
        // check its is parameter context
        if (parenthesizedArgList.isPresent() && isWithinParenthesis(linePosition,
                parenthesizedArgList.get().openParenToken(),
                parenthesizedArgList.get().closeParenToken())) {
            if (bLangNode instanceof BLangTypeInit &&
                    ((BLangInvocation) ((BLangTypeInit) bLangNode).
                    initInvocation).symbol != null) {
                List<BVarSymbol> params = ((BInvokableSymbol) (((BLangInvocation) ((BLangTypeInit) bLangNode).
                        initInvocation).symbol)).params;
                int argIndex = 0;
                for (BLangExpression bLangExpression : ((BLangTypeInit) bLangNode).argsExpr) {
                    if (isPosWithinRange(linePosition, bLangExpression.getPosition().lineRange())) {
                        if (argIndex < params.size()) {
                            return getTypeFromBType(params.get(argIndex).getType());
                        }
                    }

                    argIndex += 1;
                    if (params.size() < argIndex) {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        return getExpectedType(bLangNode);
    }
    @Override
    public Optional<TypeSymbol> transform(IfElseStatementNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (isPosWithinRange(linePosition, node.condition().lineRange()) &&
                bLangNode instanceof BLangIf) {
            return getExpectedType(((BLangIf) bLangNode).expr);
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(WhileStatementNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (isPosWithinRange(linePosition, node.condition().lineRange())
                && bLangNode instanceof BLangWhile) {
                return getExpectedType(((BLangWhile) bLangNode).expr);
            }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(DefaultableParameterNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(ExpressionFunctionBodyNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.expression().lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    protected Optional<TypeSymbol> transformSyntaxNode(Node node) {
        return this.visit(node.parent());
    }

    @Override
    public Optional<TypeSymbol> transform(IndexedExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(RemoteMethodCallActionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangInvocation.BLangActionInvocation) {
            BLangInvocation.BLangActionInvocation bLangActionInvocation = (BLangInvocation.BLangActionInvocation)
                    nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
            if (isWithinParenthesis(linePosition, node.openParenToken(), node.closeParenToken())) {
                if (bLangActionInvocation.argExprs.size() == 0) {
                    return getTypeFromBType(((BInvokableSymbol) bLangActionInvocation.symbol).
                            getParameters().get(0).getType());
                }

                Optional<BLangExpression> argument = bLangActionInvocation.argExprs.stream().filter
                        (argumentNode -> isPosWithinRange(linePosition, argumentNode.getPosition()
                                .lineRange())).findFirst();
                if (argument.isPresent()) {
                    return getExpectedType(argument.get());
                }

            }

            return getExpectedType(bLangNode);
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(ListConstructorExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        Optional<TypeSymbol> extractedType = getExpectedType(bLangNode);
        if (!extractedType.isPresent()) {
            return Optional.empty();
        }

        if (extractedType.get().typeKind() != TypeDescKind.ARRAY) {
            return Optional.empty();
        }

        BArrayType bArrayType = (BArrayType) ((BallerinaArrayTypeSymbol) extractedType.get()).getBType();
        if (bArrayType.eType != null && bArrayType.eType.getKind() != TypeKind.OTHER) {
            return Optional.of(typesFactory.getTypeDescriptor(bArrayType.eType));
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(CaptureBindingPatternNode node) {
        Optional<Symbol> variableSymbol = this.getSymbolByName(node.variableName().text());
        if (variableSymbol.isEmpty()) {
            return Optional.empty();
        }
        
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode.getBType().getKind() == TypeKind.MAP) {
            return  getTypeFromBType(bLangNode.getBType());
        }

        BType bType = bLangNode.getBType();
        if (bLangNode.getBType().getKind() == TypeKind.OTHER) {
            return Optional.empty();
        }

        return Optional.of(typesFactory.getTypeDescriptor(bType));
    }

    @Override
    public Optional<TypeSymbol> transform(MappingConstructorExpressionNode node) {
        return node.parent().apply(this);
    }

    @Override
    public Optional<TypeSymbol> transform(WaitActionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangWaitExpr) {
            BLangWaitExpr waitExpr = (BLangWaitExpr) bLangNode;
            for (BLangExpression bLangExpression : waitExpr.exprList) {
                if (isPosWithinRange(linePosition, bLangExpression.getPosition().lineRange())) {
                    return getExpectedType(bLangExpression);
                }
            }

            return getExpectedType(waitExpr);
        }

        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(ErrorConstructorExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode.getKind() == ERROR_CONSTRUCTOR_EXPRESSION) {
            if (isWithinParenthesis(linePosition, node.openParenToken(),
                    node.closeParenToken())) {
                return Optional.of(typesFactory.getTypeDescriptor(((BErrorType)
                        ((BLangErrorConstructorExpr) bLangNode).expectedType).detailType));
            }

            return Optional.of(typesFactory.getTypeDescriptor(((BLangErrorConstructorExpr) bLangNode).expectedType));
        }
        return Optional.empty();
    }

    @Override
    public Optional<TypeSymbol> transform(NamedArgumentNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(TableConstructorExpressionNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (!(bLangNode instanceof BLangTableConstructorExpr)) {
            return Optional.empty();
        }

        if (node.keySpecifier().isPresent() &&
                isWithinParenthesis(linePosition, node.keySpecifier().get().openParenToken(),
                        node.keySpecifier().get().closeParenToken())) {
            BType expectedType = ((BLangTableConstructorExpr) bLangNode).expectedType;
            if (expectedType instanceof BTableType) {
                return Optional.of(typesFactory.getTypeDescriptor(Objects.
                        requireNonNullElse(((BTableType) expectedType).constraint, expectedType)));

            }

            if (expectedType instanceof BTypeReferenceType) {
                BType referredType = ((BTypeReferenceType) expectedType).referredType;
                return Optional.of(typesFactory.getTypeDescriptor(Objects.
                        requireNonNullElse(((BTableType) referredType).constraint, expectedType)));

            }

        } else if (isWithinParenthesis(linePosition, node.openBracket(), node.closeBracket())) {
            if (!((((BLangTableConstructorExpr) bLangNode).expectedType) instanceof BTypeReferenceType)) {
                BType constraint = ((BTableType) ((BLangTableConstructorExpr) bLangNode).expectedType).constraint;
                return Optional.of(typesFactory.getTypeDescriptor(constraint));
            }

            BType rowType = ((BTableType) ((BTypeReferenceType) ((BLangTableConstructorExpr) bLangNode).expectedType)
                    .referredType).constraint;
            return Optional.of(typesFactory.getTypeDescriptor(rowType));
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(RecordFieldWithDefaultValueNode node) {
        BLangNode bLangNode = nodeFinder.lookup(this.bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        return getExpectedType(bLangNode);
    }

    @Override
    public Optional<TypeSymbol> transform(SelectClauseNode node) {
        BLangNode bLangNode = nodeFinder.lookup(bLangCompilationUnit, node.lineRange());
        if (bLangNode == null) {
            return Optional.empty();
        }

        if (bLangNode instanceof BLangSelectClause) {
            BType expectedType = ((BLangExpression) ((BLangSelectClause) bLangNode).getExpression()).expectedType;
            return getTypeFromBType(expectedType);
        }

        return Optional.empty();
    }

    private Optional<TypeSymbol> visit(Node node) {
        if (node == null || visitedNodes.contains(node)) {
            return Optional.empty();
        }

        visitedNodes.add(node);
        return node.apply(this);
    }

    @Override
    public Optional<TypeSymbol> transform(FromClauseNode node) {
        if (isPosWithinRange(linePosition, node.fromKeyword().lineRange()) ||
                isPosWithinRange(linePosition, node.inKeyword().lineRange())) {
            return Optional.empty();
        }

        if (!(node.inKeyword().lineRange().endLine().line() <= linePosition.line() &&
                node.inKeyword().lineRange().endLine().offset() <= linePosition.offset())) {
            BLangNode expressionNode = nodeFinder.lookup(bLangCompilationUnit, node.expression().lineRange());
            if (expressionNode != null) {
                BType bType = expressionNode.getBType();
                switch (bType.getKind()) {
                    case ARRAY:
                        return Optional.of(typesFactory.getTypeDescriptor((((BArrayType)
                                expressionNode.getBType()).eType)));
                    case STRING:
                        return Optional.of(semanticModel.types().STRING);
                    case TABLE:
                        return Optional.of(typesFactory.getTypeDescriptor((((BTableType)
                                expressionNode.getBType()).constraint)));
                    case STREAM:
                        return Optional.of(typesFactory.getTypeDescriptor(((BStreamType)
                                expressionNode.getBType()).constraint));
                    case XML:
                        return Optional.of(typesFactory.getTypeDescriptor(((BXMLType)
                                expressionNode.getBType()).constraint));
                    case MAP:
                        return Optional.of(typesFactory.getTypeDescriptor(((BMapType)
                                expressionNode.getBType()).constraint));
                    default:
                        break;
                }
            }
        }

        Optional<TypeSymbol> typeSymbol = semanticModel.typeOf(node.expression());
        if (typeSymbol.isPresent()) {
            return typeSymbol;
        }

        Optional<Symbol> optionalSymbol = semanticModel.symbol(node.typedBindingPattern().bindingPattern());
        if (optionalSymbol.isEmpty()) {
            return Optional.empty();
        }

        typeSymbol = SymbolUtils.getTypeDescriptor(optionalSymbol.get());
        if (typeSymbol.isEmpty() || typeSymbol.get().typeKind() == TypeDescKind.COMPILATION_ERROR) {
            return Optional.empty();
        }

        return Optional.of(buildUnionOfIterables(typeSymbol.get(), semanticModel));
    }

    private Optional<TypeSymbol> getExpectedType(BLangNode node) {
        if (node == null) {
            return Optional.empty();
        }

        BType bType = null;
        switch (node.getKind()) {
            case RECORD_LITERAL_KEY_VALUE:
            case USER_DEFINED_TYPE:
                bType = node.getBType();
                break;
            case  VARIABLE:
                if (((BLangSimpleVariable) node).expr != null) {
                    bType = ((BLangSimpleVariable) node).expr.expectedType;
                }

                break;
            case TABLE_CONSTRUCTOR_EXPR:
                bType = ((BLangTableConstructorExpr) node).expectedType;
                break;
            case RECORD_LITERAL_EXPR:
                bType = ((BLangRecordLiteral) node).expectedType;
                break;
            case SIMPLE_VARIABLE_REF:
                if (node instanceof BLangRecordLiteral) {
                    BLangExpression bLangExpression = ((BLangRecordLiteral) node).impConversionExpr;
                    if (bLangExpression != null && bLangExpression.getBType().getKind() == TypeKind.ANY) {
                        bType = bLangExpression.getBType();
                        break;
                    }
                }

                bType = ((BLangSimpleVarRef) node).expectedType;
                break;
            case NAMED_ARGS_EXPR:
                bType = ((BLangNamedArgsExpression) node).expectedType;
                break;
            case LIST_CONSTRUCTOR_EXPR:
                bType = ((BLangListConstructorExpr) node).expectedType;
                break;
            case LITERAL:
                bType = ((BLangLiteral) node).expectedType;
                break;
            case NUMERIC_LITERAL:
                bType = ((BLangNumericLiteral) node).expectedType;
                break;
            case FIELD_BASED_ACCESS_EXPR:
                bType = ((BLangFieldBasedAccess) node).expectedType;
                break;
            case TYPE_INIT_EXPR:
                bType = ((BLangTypeInit) node).expectedType;
                break;
            case INDEX_BASED_ACCESS_EXPR:
                bType = ((BLangIndexBasedAccess) node).expectedType;
                break;
            case INVOCATION:
                bType = ((BLangInvocation) node).expectedType;
                if (bType.getKind() == TypeKind.OTHER && ((BLangInvocation) node).symbol != null) {
                    bType = ((BInvokableSymbol) ((BLangInvocation) node).symbol).retType;
                }
                break;
            default:
        }

        if (bType != null && bType.getKind() != TypeKind.OTHER) {
            return Optional.of(typesFactory.getTypeDescriptor(bType));
        }

        return Optional.empty();
    }

    private Optional<TypeSymbol> getTypeFromBType(BType type) {
        BType bType;
        bType = type;
        if (type.getKind() == TypeKind.MAP) {
            bType = ((BMapType) type).constraint;
        }

        if (bType.getKind() != TypeKind.OTHER) {
            return Optional.of(typesFactory.getTypeDescriptor(bType));
        }

        return Optional.empty();
    }

    private Optional<TypeSymbol> getSymbolType(BSymbol symbol) {
        BType bType = null;
        if (symbol.getKind() == SymbolKind.VARIABLE) {
            bType = symbol.getType();
        } else if (symbol.getKind() == SymbolKind.FUNCTION) {
            bType = symbol.getType();
        }

        if (bType != null && bType.getKind() != TypeKind.OTHER) {
            return Optional.of(typesFactory.getTypeDescriptor(bType));
        }

        return Optional.empty();
    }

    private Optional<Symbol> getSymbolByName(String name) {
        return this.semanticModel.visibleSymbols(document, linePosition).stream()
                .filter((symbol -> symbol.getName().orElse("").equals(name)))
                .findFirst();
    }

    private Optional<Symbol> getSymbolByName(String name, Predicate<Symbol> predicate) {
        Predicate<Symbol> namePredicate = symbol -> symbol.getName().orElse("").equals(name);
        return this.semanticModel.visibleSymbols(document, linePosition).stream()
                .filter(namePredicate.and(predicate))
                .findFirst();
    }

    public Optional<ModuleSymbol> searchModuleForAlias(String alias) {
        List<Symbol> visibleSymbols = this.semanticModel.visibleSymbols(document, linePosition);
        for (Symbol symbol : visibleSymbols) {
            if (symbol.kind() == MODULE && Objects.equals(symbol.getName().orElse(null), alias)) {
                return Optional.of((ModuleSymbol) symbol);
            }
        }

        return Optional.empty();
    }

    private static String getAlias(QualifiedNameReferenceNode qNameRef) {
        String alias = qNameRef.modulePrefix().text();
        return getAlias(alias);
    }

    private static String getAlias(String alias) {
        return alias.startsWith("'") ? alias.substring(1) : alias;
    }

    public List<Symbol> getModuleContent(QualifiedNameReferenceNode qNameRef,
                                                Predicate<Symbol> predicate) {
        Optional<ModuleSymbol> module = searchModuleForAlias(getAlias(qNameRef));
        return module.map(moduleSymbol -> moduleSymbol.allSymbols().stream()
                        .filter(predicate)
                        .collect(Collectors.toList()))
                .orElseGet(ArrayList::new);
    }

    private Optional<Symbol> getTypeFromQNameReference(QualifiedNameReferenceNode node, Predicate<Symbol> predicate) {
        List<Symbol> moduleContent = getModuleContent(node, predicate);
        if (moduleContent.size() != 1) {
            return Optional.empty();
        }

        return Optional.ofNullable(moduleContent.get(0));
    }

    private Optional<TypeSymbol> getParamType(BLangNode bLangNode) {
        BSymbol symbol = ((BLangInvocation) bLangNode).symbol;
        if (symbol == null) {
            return Optional.empty();
        }

        BSymbol paramSymbol = ((BInvokableSymbol) symbol).params.get(0);
        return getSymbolType(paramSymbol);
    }

    private UnionTypeSymbol buildUnionOfIterables(TypeSymbol typeSymbol, SemanticModel semanticModel) {
        Types types = semanticModel.types();
        TypeBuilder builder = types.builder();
        List<TypeSymbol> unionTypeMembers = new ArrayList<>(
                List.of(builder.ARRAY_TYPE.withType(typeSymbol).build(),
                        builder.MAP_TYPE.withTypeParam(typeSymbol).build(),
                        builder.STREAM_TYPE.withValueType(typeSymbol).build()));
        if (getRawType(typeSymbol).typeKind() == TypeDescKind.RECORD) {
            try {
                unionTypeMembers
                        .add(builder.TABLE_TYPE.withRowType(getRawType(typeSymbol)).build());
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (typeSymbol instanceof StringTypeSymbol) {
            unionTypeMembers.add(types.STRING);
        }
        if (typeSymbol.typeKind() == TypeDescKind.XML) {
            unionTypeMembers.add(types.XML);
        }
        return builder.UNION_TYPE
                .withMemberTypes(unionTypeMembers.toArray(TypeSymbol[]::new)).build();
    }

    public static TypeSymbol getRawType(TypeSymbol typeDescriptor) {
        if (typeDescriptor.typeKind() == TypeDescKind.INTERSECTION) {
            return getRawType(((IntersectionTypeSymbol) typeDescriptor).effectiveTypeDescriptor());
        }
        if (typeDescriptor.typeKind() == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeDescriptor;
            if (typeRef.typeDescriptor().typeKind() == TypeDescKind.INTERSECTION) {
                return getRawType(((IntersectionTypeSymbol) typeRef.typeDescriptor()).effectiveTypeDescriptor());
            }
            return typeRef.typeDescriptor();
        }
        return typeDescriptor;
    }
}
