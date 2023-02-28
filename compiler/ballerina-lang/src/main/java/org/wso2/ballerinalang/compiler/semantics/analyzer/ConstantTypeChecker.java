package org.wso2.ballerinalang.compiler.semantics.analyzer;

import io.ballerina.tools.diagnostics.DiagnosticCode;
import io.ballerina.tools.diagnostics.Location;
import org.ballerinalang.model.TreeBuilder;
import org.ballerinalang.model.elements.Flag;
import org.ballerinalang.model.elements.MarkdownDocAttachment;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.symbols.SymbolOrigin;
import org.ballerinalang.model.tree.NodeKind;
import org.ballerinalang.model.tree.OperatorKind;
import org.ballerinalang.model.tree.expressions.RecordLiteralNode;
import org.ballerinalang.util.diagnostic.DiagnosticErrorCode;
import org.wso2.ballerinalang.compiler.diagnostic.BLangDiagnosticLog;
import org.wso2.ballerinalang.compiler.parser.BLangAnonymousModelHelper;
import org.wso2.ballerinalang.compiler.parser.BLangMissingNodesHelper;
import org.wso2.ballerinalang.compiler.semantics.model.Scope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolEnv;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.TypeVisitor;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.*;
import org.wso2.ballerinalang.compiler.semantics.model.types.*;
import org.wso2.ballerinalang.compiler.tree.*;
import org.wso2.ballerinalang.compiler.tree.expressions.*;
import org.wso2.ballerinalang.compiler.tree.types.BLangRecordTypeNode;
import org.wso2.ballerinalang.compiler.util.*;
import org.wso2.ballerinalang.util.Flags;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.BiFunction;

import static org.ballerinalang.model.symbols.SymbolOrigin.SOURCE;
import static org.ballerinalang.model.symbols.SymbolOrigin.VIRTUAL;

/**
 * @since 2201.4.0
 */
public class ConstantTypeChecker extends SimpleBLangNodeAnalyzer<ConstantTypeChecker.AnalyzerData> {
    private static final CompilerContext.Key<ConstantTypeChecker> CONSTANT_TYPE_CHECKER_KEY = new CompilerContext.Key<>();

    private final SymbolTable symTable;
    private final Names names;
    private final SymbolResolver symResolver;
    private final BLangDiagnosticLog dlog;
    private final BLangMissingNodesHelper missingNodesHelper;
    private final Types types;
    private final TypeChecker typeChecker;
    private final TypeResolver typeResolver;
    private BLangAnonymousModelHelper anonymousModelHelper;

    public ConstantTypeChecker(CompilerContext context) {
        context.put(CONSTANT_TYPE_CHECKER_KEY, this);

        this.symTable = SymbolTable.getInstance(context);
        this.names = Names.getInstance(context);
        this.symResolver = SymbolResolver.getInstance(context);
        this.dlog = BLangDiagnosticLog.getInstance(context);
        this.types = Types.getInstance(context);
        this.anonymousModelHelper = BLangAnonymousModelHelper.getInstance(context);
        this.typeChecker = TypeChecker.getInstance(context);
        this.typeResolver = TypeResolver.getInstance(context);
        this.missingNodesHelper = BLangMissingNodesHelper.getInstance(context);
    }

    public static ConstantTypeChecker getInstance(CompilerContext context) {
        ConstantTypeChecker constTypeChecker = context.get(CONSTANT_TYPE_CHECKER_KEY);
        if (constTypeChecker == null) {
            constTypeChecker = new ConstantTypeChecker(context);
        }

        return constTypeChecker;
    }

    public BType checkConstExpr(BLangExpression expr, SymbolEnv env, AnalyzerData data) {
        return checkConstExpr(expr, env, symTable.noType, data);
    }

    public BType checkConstExpr(BLangExpression expr, AnalyzerData data) {
        return checkConstExpr(expr, data.env, symTable.noType, data);
    }

    public BType checkConstExpr(BLangExpression expr, SymbolEnv env, BType expType, AnalyzerData data) {
        return checkConstExpr(expr, env, expType, DiagnosticErrorCode.INCOMPATIBLE_TYPES, data);
    }

    public BType checkConstExpr(BLangExpression expr, BType expType, AnalyzerData data) {
        return checkConstExpr(expr, data.env, expType, DiagnosticErrorCode.INCOMPATIBLE_TYPES, data);
    }

    public BType checkConstExpr(BLangExpression expr, SymbolEnv env) {
        return checkConstExpr(expr, env, symTable.noType, new Stack<>());
    }

    public BType checkConstExpr(BLangExpression expr, SymbolEnv env, BType expType, Stack<SymbolEnv> prevEnvs) {
        final AnalyzerData data = new AnalyzerData();
        data.env = env;
        data.prevEnvs = prevEnvs;
        data.commonAnalyzerData.queryFinalClauses = new Stack<>();
        data.commonAnalyzerData.queryEnvs = new Stack<>();
        return checkConstExpr(expr, env, expType, DiagnosticErrorCode.INCOMPATIBLE_TYPES, data);
    }

    public BType checkConstExpr(BLangExpression expr, SymbolEnv env, BType expType, DiagnosticCode diagCode,
                                AnalyzerData data) {
        if (expr.typeChecked) {
            return expr.getBType();
        }

        SymbolEnv prevEnv = data.env;
        BType preExpType = data.expType;
        DiagnosticCode preDiagCode = data.diagCode;
        data.env = env;
        data.diagCode = diagCode;
        data.expType = expType;
        data.isTypeChecked = true;
        expr.expectedType = expType;

        expr.accept(this, data);

        expr.setTypeCheckedType(data.resultType);
        expr.typeChecked = data.isTypeChecked;
        data.env = prevEnv;
        data.expType = preExpType;
        data.diagCode = preDiagCode;

        validateAndSetExprExpectedType(expr, data);

        return data.resultType;
    }

    public void validateAndSetExprExpectedType(BLangExpression expr, AnalyzerData data) {
        if (data.resultType.tag == TypeTags.SEMANTIC_ERROR) {
            return;
        }

        // If the expected type is a map, but a record type is inferred due to the presence of `readonly` fields in
        // the mapping constructor expression, we don't override the expected type.
        if (expr.getKind() == NodeKind.RECORD_LITERAL_EXPR && expr.expectedType != null &&
                Types.getReferredType(expr.expectedType).tag == TypeTags.MAP
                && Types.getReferredType(expr.getBType()).tag == TypeTags.RECORD) {
            return;
        }

        expr.expectedType = data.resultType;
    }

    @Override
    public void analyzeNode(BLangNode node, AnalyzerData data) {
        // Temporarily Added.
        dlog.error(node.pos, DiagnosticErrorCode.CONSTANT_DECLARATION_NOT_YET_SUPPORTED, node);
        data.resultType = symTable.semanticError;
    }

    @Override
    public void visit(BLangPackage node, AnalyzerData data) {

    }

    @Override
    public void visit(BLangLiteral literalExpr, AnalyzerData data) {
        BType literalType = setLiteralValueAndGetType(literalExpr, data); // Get the literal type.
        if (literalType == symTable.semanticError) {
            data.resultType = literalType;
            return;
        }

        if (literalExpr.isFiniteContext) {
            return;
        }
        data.resultType = getFiniteType(literalExpr.value, data.constantSymbol, literalExpr.pos, literalType);
    }

    @Override
    public void visit(BLangSimpleVarRef varRefExpr, AnalyzerData data) {
        // Set error type as the actual type.
        BType actualType = symTable.semanticError;

        Name varName = names.fromIdNode(varRefExpr.variableName);
        if (varName == Names.IGNORE) {
            varRefExpr.setBType(this.symTable.anyType);

            // If the variable name is a wildcard('_'), the symbol should be ignorable.
            varRefExpr.symbol = new BVarSymbol(0, true, varName,
                    names.originalNameFromIdNode(varRefExpr.variableName),
                    data.env.enclPkg.symbol.pkgID, varRefExpr.getBType(), data.env.scope.owner,
                    varRefExpr.pos, VIRTUAL);

            data.resultType = varRefExpr.getBType();
            return;
        }

        Name compUnitName = typeChecker.getCurrentCompUnit(varRefExpr);
        varRefExpr.pkgSymbol =
                symResolver.resolvePrefixSymbol(data.env, names.fromIdNode(varRefExpr.pkgAlias), compUnitName);
        if (varRefExpr.pkgSymbol == symTable.notFoundSymbol) {
            varRefExpr.symbol = symTable.notFoundSymbol;
            dlog.error(varRefExpr.pos, DiagnosticErrorCode.UNDEFINED_MODULE, varRefExpr.pkgAlias);
        }

        if (varRefExpr.pkgSymbol != symTable.notFoundSymbol) {
            BSymbol symbol = getSymbolOfVarRef(varRefExpr.pos, data.env, names.fromIdNode(varRefExpr.pkgAlias), varName, data);

            if (symbol == symTable.notFoundSymbol) {
                data.resultType = symTable.semanticError;
                varRefExpr.symbol = symbol; // Set notFoundSymbol
                logUndefinedSymbolError(varRefExpr.pos, varName.value);
                return;
            }

            if ((symbol.tag & SymTag.CONSTANT) == SymTag.CONSTANT) {
                // Check whether the referenced expr is a constant.
                BConstantSymbol constSymbol = (BConstantSymbol) symbol;
                varRefExpr.symbol = constSymbol;
                actualType = symbol.type;
            } else {
                varRefExpr.symbol = symbol;
                dlog.error(varRefExpr.pos, DiagnosticErrorCode.EXPRESSION_IS_NOT_A_CONSTANT_EXPRESSION);
            }
        }

        data.resultType = actualType;
    }

    public void visit(BLangListConstructorExpr listConstructor, AnalyzerData data) {
        boolean containErrors = false;
        List<BType> memberTypes = new ArrayList<>();
        for (BLangExpression expr : listConstructor.exprs) {
            if (expr.getKind() == NodeKind.LIST_CONSTRUCTOR_SPREAD_OP) {
                // Spread operator expr.
                BLangExpression spreadOpExpr = ((BLangListConstructorExpr.BLangListConstructorSpreadOpExpr) expr).expr;
                BType spreadOpExprType = checkConstExpr(spreadOpExpr, data);
                BType type = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(spreadOpExprType));
                if (type.tag != TypeTags.TUPLE) {
                    // Finalized constant type should be a tuple type (cannot be an array type or any other).
                    containErrors = true;
                    continue;
                }

                // Add the members from spread operator into current tuple type.
                for (BType memberType : ((BTupleType) type).getTupleTypes()) {
                    memberTypes.add(memberType);
                }
                continue;
            }

            BType tupleMemberType = checkConstExpr(expr, data.expType, data);
            if (tupleMemberType == symTable.semanticError) {
                containErrors = true;
                continue;
            }
            memberTypes.add(tupleMemberType);
        }

        if (containErrors) {
            data.resultType = symTable.semanticError;
            return;
        }

        // Create new tuple type using inferred members.
        BTypeSymbol tupleTypeSymbol = Symbols.createTypeSymbol(SymTag.TUPLE_TYPE, 0, Names.EMPTY,
                data.env.enclPkg.symbol.pkgID, null, data.env.scope.owner, listConstructor.pos, SOURCE);
        List<BTupleMember> members = new ArrayList<>();
        memberTypes.forEach(m ->
                members.add(new BTupleMember(m, Symbols.createVarSymbolForTupleMember(m))));
        BTupleType tupleType = new BTupleType(tupleTypeSymbol, members);
        tupleType.tsymbol.type = tupleType;
        data.resultType = tupleType;
    }

    public void visit(BLangRecordLiteral.BLangRecordVarNameField varRefExpr, AnalyzerData data) {
        BType actualType = symTable.semanticError;

        Name varName = names.fromIdNode(varRefExpr.variableName);
        if (varName == Names.IGNORE) {
            varRefExpr.setBType(this.symTable.anyType);

            // If the variable name is a wildcard('_'), the symbol should be ignorable.
            varRefExpr.symbol = new BVarSymbol(0, true, varName,
                    names.originalNameFromIdNode(varRefExpr.variableName),
                    data.env.enclPkg.symbol.pkgID, varRefExpr.getBType(), data.env.scope.owner,
                    varRefExpr.pos, VIRTUAL);

            data.resultType = varRefExpr.getBType();
            return;
        }

        Name compUnitName = typeChecker.getCurrentCompUnit(varRefExpr);
        varRefExpr.pkgSymbol =
                symResolver.resolvePrefixSymbol(data.env, names.fromIdNode(varRefExpr.pkgAlias), compUnitName);
        if (varRefExpr.pkgSymbol == symTable.notFoundSymbol) {
            varRefExpr.symbol = symTable.notFoundSymbol;
            dlog.error(varRefExpr.pos, DiagnosticErrorCode.UNDEFINED_MODULE, varRefExpr.pkgAlias);
        }

        if (varRefExpr.pkgSymbol != symTable.notFoundSymbol) {
            BSymbol symbol = getSymbolOfVarRef(varRefExpr.pos, data.env, names.fromIdNode(varRefExpr.pkgAlias), varName, data);

            if (symbol == symTable.notFoundSymbol) {
                data.resultType = symTable.semanticError;
                return;
            }

            if ((symbol.tag & SymTag.CONSTANT) == SymTag.CONSTANT) {
                // Check whether the referenced expr is a constant.
                BConstantSymbol constSymbol = (BConstantSymbol) symbol;
                varRefExpr.symbol = constSymbol;
                actualType = symbol.type;
            } else {
                varRefExpr.symbol = symbol;
                dlog.error(varRefExpr.pos, DiagnosticErrorCode.EXPRESSION_IS_NOT_A_CONSTANT_EXPRESSION);
            }
        }

        data.resultType = actualType;
    }

    public void visit(BLangRecordLiteral recordLiteral, AnalyzerData data) {
        boolean containErrors = false;
        SymbolEnv env = data.env;
        PackageID pkgID = env.enclPkg.symbol.pkgID;
        BRecordTypeSymbol recordSymbol = createRecordTypeSymbol(pkgID, recordLiteral.pos, VIRTUAL, data);
        LinkedHashMap<String, BField> inferredFields = new LinkedHashMap<>();
        List<RecordLiteralNode.RecordField> computedFields = new ArrayList<>();

        for (RecordLiteralNode.RecordField field : recordLiteral.fields) {
            if (field.isKeyValueField()) {
                BLangRecordLiteral.BLangRecordKeyValueField keyValue =
                        (BLangRecordLiteral.BLangRecordKeyValueField) field;
                BLangRecordLiteral.BLangRecordKey key = keyValue.key;

                if (key.computedKey) {
                    // Computed fields can overwrite the existing field values.
                    // Therefore, Computed key fields should be evaluated at the end.
                    // Temporarily added them into a list.
                    computedFields.add(field);
                    continue;
                }

                BLangExpression keyValueExpr = keyValue.valueExpr;
                BType keyValueType = checkConstExpr(keyValueExpr, data.expType, data);
                BLangExpression keyExpr = key.expr;

                // Add the resolved field.
                if (!addFields(inferredFields, keyValueType, getKeyName(keyExpr), keyExpr.pos, recordSymbol, false)) {
                    containErrors = true;
                }
            } else if (field.getKind() == NodeKind.SIMPLE_VARIABLE_REF) {
                BLangRecordLiteral.BLangRecordVarNameField varNameField =
                        (BLangRecordLiteral.BLangRecordVarNameField) field;
                BType varRefType = checkConstExpr(varNameField, data.expType, data);
                if (!addFields(inferredFields, Types.getReferredType(varRefType), getKeyName(varNameField),
                        varNameField.pos, recordSymbol, false)) {
                    containErrors = true;
                }
            } else { // Spread Field
                BLangExpression fieldExpr = ((BLangRecordLiteral.BLangRecordSpreadOperatorField) field).expr;
                BType spreadOpType = checkConstExpr(fieldExpr, data.expType, data);
                BType type = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(spreadOpType));
                if (type.tag != TypeTags.RECORD) {
                    containErrors = true;
                    continue;
                }

                BRecordType recordType = (BRecordType) type;
                for (BField recField : recordType.fields.values()) {
                    if (!addFields(inferredFields, Types.getReferredType(recField.type), recField.name.value,
                            fieldExpr.pos, recordSymbol, false)) {
                        containErrors = true;
                    }
                }
            }
        }

        for (RecordLiteralNode.RecordField field : computedFields) {
            BLangRecordLiteral.BLangRecordKeyValueField keyValue = (BLangRecordLiteral.BLangRecordKeyValueField) field;
            BLangRecordLiteral.BLangRecordKey key = keyValue.key;
            BType fieldName = checkConstExpr(key.expr, data);
            if (fieldName.tag == TypeTags.FINITE && ((BFiniteType) fieldName).getValueSpace().size() == 1) {
                BLangLiteral fieldNameLiteral = (BLangLiteral) ((BFiniteType) fieldName).getValueSpace().iterator().next();
                if (fieldNameLiteral.getBType().tag == TypeTags.STRING) {
                    BType keyValueType = checkConstExpr(keyValue.valueExpr, data);
                    if (!addFields(inferredFields, Types.getReferredType(keyValueType),
                            fieldNameLiteral.getValue().toString(), key.pos, recordSymbol, true)) {
                        containErrors = true;
                    }
                    continue;
                }
            }
            dlog.error(key.pos, DiagnosticErrorCode.INCOMPATIBLE_TYPES, fieldName, symTable.stringType);
            containErrors = true;
        }

        if (containErrors) {
            data.resultType = symTable.semanticError;
            return;
        }

        BRecordType recordType = new BRecordType(recordSymbol);
        recordType.restFieldType = symTable.noType;
        recordType.fields = inferredFields;
        recordSymbol.type = recordType;
        recordType.tsymbol = recordSymbol;
        recordType.sealed = true;
        data.resultType = recordType;
    }

    @Override
    public void visit(BLangBinaryExpr binaryExpr, AnalyzerData data) {
        BType lhsType = checkConstExpr(binaryExpr.lhsExpr, data.expType, data);
        BType rhsType = checkConstExpr(binaryExpr.rhsExpr, data.expType, data);

        Location pos = binaryExpr.pos;

        if (lhsType == symTable.semanticError || rhsType == symTable.semanticError) {
            data.resultType = symTable.semanticError;
            return;
        } else {
            // Resolve the operator symbol and corresponding result type according to different operand types.
            BSymbol opSymbol = symTable.notFoundSymbol;

            if (lhsType.tag == TypeTags.UNION && rhsType.tag == TypeTags.UNION) {
                // Both the operands are unions.
                opSymbol = getOpSymbolBothUnion(binaryExpr.opKind, (BUnionType) lhsType, (BUnionType) rhsType,
                        binaryExpr, data);
            }
            if (lhsType.tag == TypeTags.UNION && rhsType.tag != TypeTags.UNION) {
                // LHS is a union type.
                opSymbol = getOpSymbolLhsUnion(binaryExpr.opKind, (BUnionType) lhsType, rhsType, binaryExpr,
                        data, false);
            }
            if (lhsType.tag != TypeTags.UNION && rhsType.tag == TypeTags.UNION) {
                // RHS is a union type.
                opSymbol = getOpSymbolLhsUnion(binaryExpr.opKind, (BUnionType) rhsType, lhsType, binaryExpr,
                        data, true);
            }
            if (lhsType.tag != TypeTags.UNION && rhsType.tag != TypeTags.UNION) {
                // Both the operands are not unions.
                opSymbol = getOpSymbolBothNonUnion(lhsType, rhsType, binaryExpr, data);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                dlog.error(pos, DiagnosticErrorCode.INVALID_CONST_EXPRESSION);
                data.resultType = symTable.semanticError;
                return;
            }
            binaryExpr.opSymbol = (BOperatorSymbol) opSymbol;
        }
        BType actualType = data.resultType;
        BType resultType = types.checkType(binaryExpr, actualType, symTable.noType);
        if (resultType == symTable.semanticError) {
            dlog.error(pos, DiagnosticErrorCode.INVALID_CONST_EXPRESSION);
            data.resultType = symTable.semanticError;
            return;
        }
        BConstantSymbol constantSymbol = data.constantSymbol;
        if (resultType.tag == TypeTags.UNION) {
            Iterator<BType> iterator = ((BUnionType) resultType).getMemberTypes().iterator();
            BType expType = iterator.next();
            Object resolvedValue = calculateSingletonValue(getCorrespondingFiniteType(lhsType, expType),
                    getCorrespondingFiniteType(rhsType, expType), binaryExpr.opKind, expType, binaryExpr.pos);
            List<Object> valueList = new ArrayList<>();
            valueList.add(resolvedValue);
            while (iterator.hasNext()) {
                expType = iterator.next();
                Object value = calculateSingletonValue(getCorrespondingFiniteType(lhsType, expType),
                        getCorrespondingFiniteType(rhsType, expType), binaryExpr.opKind, expType, binaryExpr.pos);
                valueList.add(value);
            }
            iterator = ((BUnionType) resultType).getMemberTypes().iterator();
            if (valueList.size() == 0) {
                dlog.error(pos, DiagnosticErrorCode.INVALID_CONST_EXPRESSION);
                data.resultType = symTable.semanticError;
                return;
            }
            LinkedHashSet<BType> memberTypes = new LinkedHashSet<>(valueList.size());
            for (int i = 0; i < valueList.size(); i++) {
                Object value = valueList.get(i);
                BType type = iterator.next();
                if (value != null) {
                    memberTypes.add(getFiniteType(value, constantSymbol, pos, type));
                }
            }

            // Results will be union of resultant values.
            data.resultType = BUnionType.create(null, memberTypes);
            return;
        }
        Object resolvedValue = calculateSingletonValue(getCorrespondingFiniteType(lhsType, resultType),
                getCorrespondingFiniteType(rhsType, resultType), binaryExpr.opKind, resultType, binaryExpr.pos);
        if (resolvedValue == null) {
            data.resultType = symTable.semanticError;
            dlog.error(pos, DiagnosticErrorCode.INVALID_CONST_EXPRESSION);
            return;
        }
        data.resultType = getFiniteType(resolvedValue, constantSymbol, pos, resultType);
    }

    public void visit(BLangGroupExpr groupExpr, AnalyzerData data) {
        checkConstExpr(groupExpr.expression, data.expType, data);
    }

    public void visit(BLangUnaryExpr unaryExpr, AnalyzerData data) {
        BType resultType;
        BType actualType = checkConstExpr(unaryExpr.expr, data.expType, data);
        BSymbol opSymbol = symTable.notFoundSymbol;

        if (actualType.tag == TypeTags.UNION) {
            LinkedHashSet<BType> resolvedTypes = new LinkedHashSet<>(3);
            for (BType memberType : ((BUnionType) actualType).getMemberTypes()) {
                // Get the unary operator symbol and the result type for each member type.
                BSymbol resolvedSymbol = getUnaryOpSymbol(unaryExpr, memberType, data);
                if (resolvedSymbol != symTable.notFoundSymbol) {
                    opSymbol = resolvedSymbol;
                    resolvedTypes.add(getBroadType(data.resultType));
                }
            }
            if (resolvedTypes.size() == 1) {
                resultType = resolvedTypes.iterator().next();
            } else {
                resultType = BUnionType.create(null, resolvedTypes);
            }
        } else {
            opSymbol = getUnaryOpSymbol(unaryExpr, actualType, data);
            resultType = getBroadType(data.resultType);
        }

        if (opSymbol == symTable.notFoundSymbol) {
            data.resultType = symTable.semanticError;
            return;
        }

        Location pos = unaryExpr.pos;
        BConstantSymbol constantSymbol = data.constantSymbol;
        if (resultType.tag == TypeTags.UNION) {
            Iterator<BType> iterator = ((BUnionType) resultType).getMemberTypes().iterator();
            BType expType = iterator.next();
            Object resolvedValue = evaluateUnaryOperator(getCorrespondingFiniteType(actualType, expType), expType,
                    unaryExpr.operator);
            List<Object> valueList = new ArrayList<>();
            if (resolvedValue != null) {
                valueList.add(resolvedValue);
            }
            while (iterator.hasNext()) {
                expType = iterator.next();
                Object value = evaluateUnaryOperator(getCorrespondingFiniteType(actualType, expType), expType,
                        unaryExpr.operator);
                if (value != null) {
                    valueList.add(value);
                }
            }
            iterator = ((BUnionType) resultType).getMemberTypes().iterator();
            if (valueList.size() == 0) {
                data.resultType = symTable.semanticError;
                return;
            }
            LinkedHashSet<BType> memberTypes = new LinkedHashSet<>(valueList.size());
            for (int i = 0; i < valueList.size(); i++) {
                memberTypes.add(getFiniteType(valueList.get(i), constantSymbol, pos, iterator.next()));
            }

            // Results will be union of resultant values.
            data.resultType = BUnionType.create(null, memberTypes);
            return;
        }
        Object resolvedValue = evaluateUnaryOperator(getCorrespondingFiniteType(actualType, resultType), resultType,
                unaryExpr.operator);
        if (resolvedValue == null) {
            data.resultType = symTable.semanticError;
            return;
        }
        data.resultType = getFiniteType(resolvedValue, constantSymbol, pos, resultType);
    }

    private BType getBroadType(BType type) {
        if (type.tag != TypeTags.FINITE) {
            return type;
        }
        return ((BFiniteType) type).getValueSpace().iterator().next().getBType();
    }

    private BSymbol getUnaryOpSymbol(BLangUnaryExpr unaryExpr, BType type, AnalyzerData data) {
        if (type == symTable.semanticError) {
            return symTable.notFoundSymbol;
        }
        BType exprType = type;
        BSymbol symbol = symResolver.resolveUnaryOperator(unaryExpr.operator, exprType);
        if (symbol == symTable.notFoundSymbol) {
            symbol = symResolver.getUnaryOpsForTypeSets(unaryExpr.operator, exprType);
        }
        if (symbol == symTable.notFoundSymbol) {
//            dlog.error(unaryExpr.pos, DiagnosticErrorCode.UNARY_OP_INCOMPATIBLE_TYPES,
//                    unaryExpr.operator, exprType);
        } else {
            unaryExpr.opSymbol = (BOperatorSymbol) symbol;
            data.resultType = symbol.type.getReturnType();
        }
        if (symbol == symTable.notFoundSymbol) {
            exprType = ((BFiniteType) type).getValueSpace().iterator().next().getBType();
            symbol = symResolver.resolveUnaryOperator(unaryExpr.operator, exprType);
            if (symbol == symTable.notFoundSymbol) {
                symbol = symResolver.getUnaryOpsForTypeSets(unaryExpr.operator, exprType);
            }
            if (symbol == symTable.notFoundSymbol) {
//                dlog.error(unaryExpr.pos, DiagnosticErrorCode.UNARY_OP_INCOMPATIBLE_TYPES,
//                        unaryExpr.operator, exprType);
            } else {
                unaryExpr.opSymbol = (BOperatorSymbol) symbol;
                data.resultType = symbol.type.getReturnType();
            }
        }
        return symbol;
    }

    private Object calculateSingletonValue(BFiniteType lhs, BFiniteType rhs, OperatorKind kind,
                                                       BType type, Location currentPos) {
        // Calculate the value for the binary operation.
        // TODO - Handle overflows.
        if (lhs == null || rhs == null) {
            // This is a compilation error.
            // This is to avoid NPE exceptions in sub-sequent validations.
            return null;
        }

        BLangLiteral lhsLiteral = (BLangLiteral) lhs.getValueSpace().iterator().next();
        BLangLiteral rhsLiteral = (BLangLiteral) rhs.getValueSpace().iterator().next();

        // See Types.isAllowedConstantType() for supported types.
        Object lhsValue = getValue(lhsLiteral);
        Object rhsValue = getValue(rhsLiteral);
        try {
            switch (kind) {
                case ADD:
                    return calculateAddition(lhsValue, rhsValue, type);
                case SUB:
                    return calculateSubtract(lhsValue, rhsValue, type);
                case MUL:
                    return calculateMultiplication(lhsValue, rhsValue, type);
                case DIV:
                    return calculateDivision(lhsValue, rhsValue, type);
                case MOD:
                    return calculateMod(lhsValue, rhsValue, type);
                case BITWISE_AND:
                    return calculateBitWiseOp(lhsValue, rhsValue, (a, b) -> a & b, type, currentPos);
                case BITWISE_OR:
                    return calculateBitWiseOp(lhsValue, rhsValue, (a, b) -> a | b, type, currentPos);
                case BITWISE_LEFT_SHIFT:
                    return calculateBitWiseOp(lhsValue, rhsValue, (a, b) -> a << b, type, currentPos);
                case BITWISE_RIGHT_SHIFT:
                    return calculateBitWiseOp(lhsValue, rhsValue, (a, b) -> a >> b, type, currentPos);
                case BITWISE_UNSIGNED_RIGHT_SHIFT:
                    return calculateBitWiseOp(lhsValue, rhsValue, (a, b) -> a >>> b, type, currentPos);
                case BITWISE_XOR:
                    return calculateBitWiseOp(lhsValue, rhsValue, (a, b) -> a ^ b, type, currentPos);
                default:
//                    dlog.error(currentPos, DiagnosticErrorCode.CONSTANT_EXPRESSION_NOT_SUPPORTED);
            }
        } catch (NumberFormatException nfe) {
            // Ignore. This will be handled as a compiler error.
        } catch (ArithmeticException ae) {
//            dlog.error(currentPos, DiagnosticErrorCode.INVALID_CONST_EXPRESSION, ae.getMessage());
        }
        // This is a compilation error already logged.
        // This is to avoid NPE exceptions in sub-sequent validations.
        return null;
    }

    private Object getValue(BLangLiteral lhsLiteral) {
        Object value = lhsLiteral.value;
        if (value instanceof BLangConstantValue) {
            return ((BLangConstantValue) value).value;
        }
        return value;
    }

    private Object evaluateUnaryOperator(BFiniteType finiteType, BType type, OperatorKind kind) {
        // Calculate the value for the unary operation.
        BLangLiteral lhsLiteral = (BLangLiteral) finiteType.getValueSpace().iterator().next();
        Object value = getValue(lhsLiteral);
        if (value == null) {
            // This is a compilation error.
            // This is to avoid NPE exceptions in sub-sequent validations.
            return null;
        }

        try {
            switch (kind) {
                case ADD:
                    return value;
                case SUB:
                    return calculateNegation(value, type);
                case BITWISE_COMPLEMENT:
                    return calculateBitWiseComplement(value, type);
                case NOT:
                    return calculateBooleanComplement(value, type);
            }
        } catch (ClassCastException ce) {
            // Ignore. This will be handled as a compiler error.
        }
        // This is a compilation error already logged.
        // This is to avoid NPE exceptions in sub-sequent validations.
        return null;
    }

    private Object calculateBitWiseOp(Object lhs, Object rhs,
                                                  BiFunction<Long, Long, Long> func, BType type, Location currentPos) {
        switch (Types.getReferredType(type).tag) {
            case TypeTags.INT:
                Long val = func.apply((Long) lhs, (Long) rhs);
                return val;
            default:
                dlog.error(currentPos, DiagnosticErrorCode.CONSTANT_EXPRESSION_NOT_SUPPORTED);

        }
        return null;
//        return new BLangConstantValue(null, type);
    }

    private Object calculateAddition(Object lhs, Object rhs, BType type) {
        Object result = null;
        switch (Types.getReferredType(type).tag) {
            case TypeTags.INT:
            case TypeTags.BYTE: // Byte will be a compiler error.
                try {
                    result = Math.addExact((Long) lhs, (Long) rhs);
                } catch (ArithmeticException ae) {
                    return null;
                }
                break;
            case TypeTags.FLOAT:
                result = String.valueOf(Double.parseDouble(String.valueOf(lhs))
                        + Double.parseDouble(String.valueOf(rhs)));
                break;
            case TypeTags.DECIMAL:
                BigDecimal lhsDecimal = new BigDecimal(String.valueOf(lhs), MathContext.DECIMAL128);
                BigDecimal rhsDecimal = new BigDecimal(String.valueOf(rhs), MathContext.DECIMAL128);
                BigDecimal resultDecimal = lhsDecimal.add(rhsDecimal, MathContext.DECIMAL128);
                resultDecimal = types.getValidDecimalNumber(resultDecimal);
                result = resultDecimal != null ? resultDecimal.toPlainString() : null;
                break;
            case TypeTags.STRING:
                result = String.valueOf(lhs) + String.valueOf(rhs);
                break;
        }
        return result;
    }

    private Object calculateSubtract(Object lhs, Object rhs, BType type) {
        Object result = null;
        switch (Types.getReferredType(type).tag) {
            case TypeTags.INT:
            case TypeTags.BYTE: // Byte will be a compiler error.
                try {
                    result = Math.subtractExact((Long) lhs, (Long) rhs);
                } catch (ArithmeticException ae) {
                    return null;
                }
                break;
            case TypeTags.FLOAT:
                result = String.valueOf(Double.parseDouble(String.valueOf(lhs))
                        - Double.parseDouble(String.valueOf(rhs)));
                break;
            case TypeTags.DECIMAL:
                BigDecimal lhsDecimal = new BigDecimal(String.valueOf(lhs), MathContext.DECIMAL128);
                BigDecimal rhsDecimal = new BigDecimal(String.valueOf(rhs), MathContext.DECIMAL128);
                BigDecimal resultDecimal = lhsDecimal.subtract(rhsDecimal, MathContext.DECIMAL128);
                resultDecimal = types.getValidDecimalNumber(resultDecimal);
                result = resultDecimal != null ? resultDecimal.toPlainString() : null;
                break;
        }
        return result;
    }

    private Object calculateMultiplication(Object lhs, Object rhs, BType type) {
        Object result = null;
        switch (Types.getReferredType(type).tag) {
            case TypeTags.INT:
            case TypeTags.BYTE: // Byte will be a compiler error.
                try {
                    result = Math.multiplyExact((Long) lhs, (Long) rhs);
                } catch (ArithmeticException ae) {
                    return null;
                }
                break;
            case TypeTags.FLOAT:
                result = String.valueOf(Double.parseDouble(String.valueOf(lhs))
                        * Double.parseDouble(String.valueOf(rhs)));
                break;
            case TypeTags.DECIMAL:
                BigDecimal lhsDecimal = new BigDecimal(String.valueOf(lhs), MathContext.DECIMAL128);
                BigDecimal rhsDecimal = new BigDecimal(String.valueOf(rhs), MathContext.DECIMAL128);
                BigDecimal resultDecimal = lhsDecimal.multiply(rhsDecimal, MathContext.DECIMAL128);
                resultDecimal = types.getValidDecimalNumber(resultDecimal);
                result = resultDecimal != null ? resultDecimal.toPlainString() : null;
                break;
        }
        return result;
    }

    private Object calculateDivision(Object lhs, Object rhs, BType type) {
        Object result = null;
        switch (Types.getReferredType(type).tag) {
            case TypeTags.INT:
            case TypeTags.BYTE: // Byte will be a compiler error.
                if ((Long) lhs == Long.MIN_VALUE && (Long) rhs == -1) {
                    return null;
                }
                result = (Long) ((Long) lhs / (Long) rhs);
                break;
            case TypeTags.FLOAT:
                result = String.valueOf(Double.parseDouble(String.valueOf(lhs))
                        / Double.parseDouble(String.valueOf(rhs)));
                break;
            case TypeTags.DECIMAL:
                BigDecimal lhsDecimal = new BigDecimal(String.valueOf(lhs), MathContext.DECIMAL128);
                BigDecimal rhsDecimal = new BigDecimal(String.valueOf(rhs), MathContext.DECIMAL128);
                BigDecimal resultDecimal = lhsDecimal.divide(rhsDecimal, MathContext.DECIMAL128);
                resultDecimal = types.getValidDecimalNumber(resultDecimal);
                result = resultDecimal != null ? resultDecimal.toPlainString() : null;
                break;
        }
        return result;
    }

    private Object calculateMod(Object lhs, Object rhs, BType type) {
        Object result = null;
        switch (Types.getReferredType(type).tag) {
            case TypeTags.INT:
            case TypeTags.BYTE: // Byte will be a compiler error.
                result = (Long) ((Long) lhs % (Long) rhs);
                break;
            case TypeTags.FLOAT:
                result = String.valueOf(Double.parseDouble(String.valueOf(lhs))
                        % Double.parseDouble(String.valueOf(rhs)));
                break;
            case TypeTags.DECIMAL:
                BigDecimal lhsDecimal = new BigDecimal(String.valueOf(lhs), MathContext.DECIMAL128);
                BigDecimal rhsDecimal = new BigDecimal(String.valueOf(rhs), MathContext.DECIMAL128);
                BigDecimal resultDecimal = lhsDecimal.remainder(rhsDecimal, MathContext.DECIMAL128);
                result = resultDecimal.toPlainString();
                break;
        }
        return result;
    }


    private Object calculateNegationForInt(Object value) {
        return -1 * ((Long) (value));
    }

    private Object calculateNegationForFloat(Object value) {
        return String.valueOf(-1 * Double.parseDouble(String.valueOf(value)));
    }

    private Object calculateNegationForDecimal(Object value) {
        BigDecimal valDecimal = new BigDecimal(String.valueOf(value), MathContext.DECIMAL128);
        BigDecimal negDecimal = new BigDecimal(String.valueOf(-1), MathContext.DECIMAL128);
        BigDecimal resultDecimal = valDecimal.multiply(negDecimal, MathContext.DECIMAL128);
        return resultDecimal.toPlainString();
    }

    private Object calculateNegation(Object value, BType type) {
        Object result = null;

        switch (type.tag) {
            case TypeTags.INT:
                result = calculateNegationForInt(value);
                break;
            case TypeTags.FLOAT:
                result = calculateNegationForFloat(value);
                break;
            case TypeTags.DECIMAL:
                result = calculateNegationForDecimal(value);
                break;
        }

        return result;
    }

    private Object calculateBitWiseComplement(Object value, BType type) {
        Object result = null;
        if (Types.getReferredType(type).tag == TypeTags.INT) {
            result = ~((Long) (value));
        }
        return result;
    }

    private Object calculateBooleanComplement(Object value, BType type) {
        Object result = null;
        if (Types.getReferredType(type).tag == TypeTags.BOOLEAN) {
            result = !((Boolean) (value));
        }
        return result;
    }

    public BType getTypeOfLiteralWithFloatDiscriminator(BLangLiteral literalExpr, Object literalValue,
                                                        AnalyzerData data) {
        String numericLiteral = NumericLiteralSupport.stripDiscriminator(String.valueOf(literalValue));
        if (!types.validateFloatLiteral(literalExpr.pos, numericLiteral)) {
            data.resultType = symTable.semanticError;
            return symTable.semanticError;
        }
        literalExpr.value = Double.parseDouble(numericLiteral);
        return symTable.floatType;
    }

    public BType getIntegerLiteralType(BLangLiteral literalExpr, Object literalValue, AnalyzerData data) {
        // Results will be union of int, float and decimal. But when the type node is not available, the result will be
        // int.
        if (!(literalValue instanceof Long)) {
            dlog.error(literalExpr.pos, DiagnosticErrorCode.OUT_OF_RANGE, literalExpr.originalValue,
                    literalExpr.getBType());
            data.resultType = symTable.semanticError;
            return symTable.semanticError;
        } else if (data.expType == symTable.noType) {
            // Special case the missing type node scenarios.
            return symTable.intType;
        }

        LinkedHashSet<BType> memberTypes = new LinkedHashSet<>();
        memberTypes.add(symTable.intType);
        memberTypes.add(symTable.floatType);

        if (!NumericLiteralSupport.hasHexIndicator(literalExpr.originalValue)) {
            memberTypes.add(symTable.decimalType);
        }
        return BUnionType.create(null, memberTypes);
    }

    public BType setLiteralValueAndGetType(BLangLiteral literalExpr, AnalyzerData data) {
        literalExpr.isFiniteContext = false;
        Object literalValue = literalExpr.value;

        if (literalExpr.getKind() == NodeKind.NUMERIC_LITERAL) {
            NodeKind kind = ((BLangNumericLiteral) literalExpr).kind;
            if (kind == NodeKind.INTEGER_LITERAL) {
                return getIntegerLiteralType(literalExpr, literalValue, data);
            } else if (kind == NodeKind.DECIMAL_FLOATING_POINT_LITERAL) {
                if (NumericLiteralSupport.isFloatDiscriminated(literalExpr.originalValue)) {
                    return getTypeOfLiteralWithFloatDiscriminator(literalExpr, literalValue, data);
                } else if (NumericLiteralSupport.isDecimalDiscriminated(literalExpr.originalValue)) {
                    return getTypeOfLiteralWithDecimalDiscriminator(literalExpr, literalValue);
                } else {
                    return getTypeOfDecimalFloatingPointLiteral(literalExpr, literalValue, data);
                }
            } else {
                return getTypeOfHexFloatingPointLiteral(literalExpr, literalValue, data);
            }
        }

        // Get the type matching to the tag from the symbol table.
        BType literalType = symTable.getTypeFromTag(literalExpr.getBType().tag);
        if (literalType.tag == TypeTags.STRING && types.isCharLiteralValue((String) literalValue)) {
            boolean foundMember = types.isAssignableToFiniteType(symTable.noType, literalExpr);
            if (foundMember) {
                setLiteralValueForFiniteType(literalExpr, literalType, data);
                return literalType;
            }
        }

        // Byte arrays are not yet supported in constants.
//        if (literalExpr.getBType().tag == TypeTags.BYTE_ARRAY) {
//            // check whether this is a byte array
//            literalType = new BArrayType(symTable.byteType);
//        }

        return literalType;
    }

    public void setLiteralValueForFiniteType(BLangLiteral literalExpr, BType type, AnalyzerData data) {
        types.setImplicitCastExpr(literalExpr, type, data.expType);
        data.resultType = type;
        literalExpr.isFiniteContext = true;
    }

    public BType getTypeOfLiteralWithDecimalDiscriminator(BLangLiteral literalExpr, Object literalValue) {
        literalExpr.value = NumericLiteralSupport.stripDiscriminator(String.valueOf(literalValue));
        if (!types.isValidDecimalNumber(literalExpr.pos, literalExpr.value.toString())) {
            return symTable.semanticError;
        }
        return symTable.decimalType;
    }

    public BType getTypeOfDecimalFloatingPointLiteral(BLangLiteral literalExpr, Object literalValue,
                                                      AnalyzerData data) {
        // Results will be union of float and decimal. But when the type node is not available, the result will be
        // float.
        String numericLiteral = String.valueOf(literalValue);
        BType expectedType = data.expType;
        LinkedHashSet<BType> memberTypes = new LinkedHashSet<>();
        if (expectedType != symTable.noType) {
            dlog.mute();
            boolean isValidDecimal = types.isValidDecimalNumber(literalExpr.pos, numericLiteral);
            boolean isValidFloat = types.validateFloatLiteral(literalExpr.pos, numericLiteral);
            dlog.unmute();
            if (isValidDecimal) {
                memberTypes.add(symTable.decimalType);
            }
            if (isValidFloat) {
                memberTypes.add(symTable.floatType);
            } else if (memberTypes.isEmpty()) {
                data.resultType = symTable.semanticError;
                return symTable.semanticError;
            }
        } else {
            if (types.validateFloatLiteral(literalExpr.pos, numericLiteral)) {
                return symTable.floatType;
            }
            data.resultType = symTable.semanticError;
            return symTable.semanticError;
        }
        return BUnionType.create(null, memberTypes);
    }

    public BType getTypeOfHexFloatingPointLiteral(BLangLiteral literalExpr, Object literalValue,
                                                  AnalyzerData data) {
        String numericLiteral = String.valueOf(literalValue);
        if (!types.validateFloatLiteral(literalExpr.pos, numericLiteral)) {
            data.resultType = symTable.semanticError;
            return symTable.semanticError;
        }
        literalExpr.value = Double.parseDouble(numericLiteral);
        return symTable.floatType;
    }

    private BType getFiniteType(Object value, BConstantSymbol constantSymbol, Location pos, BType type) {
        switch (type.tag) {
            case TypeTags.INT:
            case TypeTags.FLOAT:
            case TypeTags.DECIMAL:
                BLangNumericLiteral numericLiteral = (BLangNumericLiteral) TreeBuilder.createNumericLiteralExpression();
                return createFiniteType(constantSymbol, updateLiteral(numericLiteral, value, type, pos));
            case TypeTags.BYTE:
                BLangNumericLiteral byteLiteral = (BLangNumericLiteral) TreeBuilder.createNumericLiteralExpression();
                return createFiniteType(constantSymbol, updateLiteral(byteLiteral, value, symTable.intType, pos));
            case TypeTags.STRING:
            case TypeTags.NIL:
            case TypeTags.BOOLEAN:
                BLangLiteral literal = (BLangLiteral) TreeBuilder.createLiteralExpression();
                return createFiniteType(constantSymbol, updateLiteral(literal, value, type, pos));
            case TypeTags.UNION:
                return createFiniteType(constantSymbol, value, (BUnionType) type, pos);
            default:
                return type;
        }
    }

    private BLangLiteral getLiteral(Object value, Location pos, BType type) {
        switch (type.tag) {
            case TypeTags.INT:
            case TypeTags.FLOAT:
            case TypeTags.DECIMAL:
                BLangNumericLiteral numericLiteral = (BLangNumericLiteral) TreeBuilder.createNumericLiteralExpression();
                return updateLiteral(numericLiteral, value, type, pos);
            case TypeTags.BYTE:
                BLangNumericLiteral byteLiteral = (BLangNumericLiteral) TreeBuilder.createNumericLiteralExpression();
                return updateLiteral(byteLiteral, value, symTable.byteType, pos);
            default:
                BLangLiteral literal = (BLangLiteral) TreeBuilder.createLiteralExpression();
                return updateLiteral(literal, value, type, pos);
        }
    }

    private BLangLiteral updateLiteral(BLangLiteral literal, Object value, BType type, Location pos) {
        literal.value = value;
        literal.isConstant = true;
        literal.setBType(type);
        literal.pos = pos;
        return literal;
    }

    private BFiniteType createFiniteType(BConstantSymbol constantSymbol, BLangExpression expr) {
        BTypeSymbol finiteTypeSymbol = Symbols.createTypeSymbol(SymTag.FINITE_TYPE, constantSymbol.flags, Names.EMPTY,
                constantSymbol.pkgID, null, constantSymbol.owner,
                constantSymbol.pos, VIRTUAL);
        BFiniteType finiteType = new BFiniteType(finiteTypeSymbol);
        finiteType.addValue(expr);
        finiteType.tsymbol.type = finiteType;
        return finiteType;
    }

    private BUnionType createFiniteType(BConstantSymbol constantSymbol, Object value, BUnionType type, Location pos) {
        LinkedHashSet<BType> memberTypes = new LinkedHashSet<>(3);
        for (BType memberType : type.getMemberTypes()) {
            BTypeSymbol finiteTypeSymbol = Symbols.createTypeSymbol(SymTag.FINITE_TYPE, constantSymbol.flags, Names.EMPTY,
                    constantSymbol.pkgID, null, constantSymbol.owner,
                    constantSymbol.pos, VIRTUAL);
            BFiniteType finiteType = new BFiniteType(finiteTypeSymbol);
//            BLangLiteral literal = (BLangLiteral) TreeBuilder.createLiteralExpression();
            Object memberValue;
            switch (memberType.tag) {
                case TypeTags.FLOAT:
                    memberValue =  value instanceof String? Double.parseDouble((String) value):((Long) value).doubleValue();
                    break;
                case TypeTags.DECIMAL:
                    memberValue = new BigDecimal(String.valueOf(value));
                    break;
                default:
                    memberValue = value;
            };
            finiteType.addValue(getLiteral(memberValue, pos, memberType));
            finiteType.tsymbol.type = finiteType;
            memberTypes.add(finiteType);
        }

        return BUnionType.create(null, memberTypes);
    }

    private BSymbol getSymbolOfVarRef(Location pos, SymbolEnv env, Name pkgAlias, Name varName, AnalyzerData data) {
        if (pkgAlias == Names.EMPTY && data.modTable.containsKey(varName.value)) {
            // modTable contains the available constants in current module.
            BLangNode node = data.modTable.get(varName.value);
            if (node.getKind() == NodeKind.CONSTANT) {
                if (!typeResolver.resolvedConstants.contains((BLangConstant) node)) {
                    typeResolver.resolveConstant(data.env, data.modTable, (BLangConstant) node);
                }
            } else {
                dlog.error(pos, DiagnosticErrorCode.EXPRESSION_IS_NOT_A_CONSTANT_EXPRESSION);
                return symTable.notFoundSymbol;
            }
        }

        // Search and get the referenced variable from different module.
        return symResolver.lookupMainSpaceSymbolInPackage(pos, env, pkgAlias, varName);
    }

    private boolean addFields(LinkedHashMap<String, BField> fields, BType keyValueType, String key, Location pos,
                           BRecordTypeSymbol recordSymbol, boolean overWriteValue) {
        Name fieldName = names.fromString(key);
        if (fields.containsKey(key)) {
            if (overWriteValue) {
                // This is used in computed field scenarios.
                BField field = fields.get(key);
                field.type = keyValueType;
                field.symbol.type = keyValueType;
                return true;
            }
            // Duplicate key.
            dlog.error(pos, DiagnosticErrorCode.DUPLICATE_KEY_IN_RECORD_LITERAL, key);
            return false;
        }
        Set<Flag> flags = new HashSet<>();
//        flags.add(Flag.REQUIRED); // All the fields in constant mapping types should be required fields.

        BVarSymbol fieldSymbol = new BVarSymbol(recordSymbol.flags, fieldName, recordSymbol.pkgID , keyValueType,
                recordSymbol, symTable.builtinPos, VIRTUAL);
        fields.put(fieldName.value, new BField(fieldName, null, fieldSymbol));
        return true;
    }

    private String getKeyName(BLangExpression key) {
        return key.getKind() == NodeKind.SIMPLE_VARIABLE_REF ?
                ((BLangSimpleVarRef) key).variableName.value : (String) ((BLangLiteral) key).value;
    }

    private BRecordTypeSymbol createRecordTypeSymbol(PackageID pkgID, Location location,
                                                     SymbolOrigin origin, AnalyzerData data) {
        SymbolEnv env = data.env;
        BRecordTypeSymbol recordSymbol =
                Symbols.createRecordSymbol(data.constantSymbol.flags,
                        names.fromString(anonymousModelHelper.getNextAnonymousTypeKey(pkgID)),
                        pkgID, null, env.scope.owner, location, origin);

        BInvokableType bInvokableType = new BInvokableType(new ArrayList<>(), symTable.nilType, null);
        BInvokableSymbol initFuncSymbol = Symbols.createFunctionSymbol(
                Flags.PUBLIC, Names.EMPTY, Names.EMPTY, env.enclPkg.symbol.pkgID, bInvokableType, env.scope.owner,
                false, symTable.builtinPos, VIRTUAL);
        initFuncSymbol.retType = symTable.nilType;
        recordSymbol.initializerFunc = new BAttachedFunction(Names.INIT_FUNCTION_SUFFIX, initFuncSymbol,
                bInvokableType, location);

        recordSymbol.scope = new Scope(recordSymbol);
        recordSymbol.scope.define(
                names.fromString(recordSymbol.name.value + "." + recordSymbol.initializerFunc.funcName.value),
                recordSymbol.initializerFunc.symbol);
        return recordSymbol;
    }

    private BFiniteType getCorrespondingFiniteType(BType type, BType expType) {
        // Here, type can be broad union type. This will return member type corresponding to expType.
        // type = 1|1.0f|1.0d, expType = float, Result = 1.0f
        if (type.tag == TypeTags.UNION) {
            for (BType memberType: ((BUnionType) type).getMemberTypes()) {
                BFiniteType result = getCorrespondingFiniteType(memberType, expType);
                if (result != null) {
                    return result;
                }
            }
        } else if (type.tag == TypeTags.FINITE && types.isAssignable(type, expType)) {
            return (BFiniteType) type;
        }
        return null;
    }

    private BSymbol getOpSymbolBothUnion(OperatorKind opKind, BUnionType lhsType, BUnionType rhsType, BLangBinaryExpr binaryExpr, AnalyzerData data) {
        BSymbol firstValidOpSymbol = symTable.notFoundSymbol;
        LinkedHashSet<BType> memberTypes = new LinkedHashSet<>();
        LinkedHashSet<BType> removableLhsMemberTypes = new LinkedHashSet<>();
        LinkedHashSet<BType> removableRhsMemberTypes = new LinkedHashSet<>();
        for (BType memberTypeRhs : rhsType.getMemberTypes()) {
            removableRhsMemberTypes.add(memberTypeRhs);
        }
        for (BType memberTypeLhs : lhsType.getMemberTypes()) {
            boolean isValidLhsMemberType = false;
            for (BType memberTypeRhs : rhsType.getMemberTypes()) {
                BSymbol resultantOpSymbol = getOpSymbol(memberTypeLhs, memberTypeRhs, binaryExpr, data);
                if (data.resultType != symTable.semanticError) {
                    memberTypes.add(data.resultType);
                    isValidLhsMemberType = true;
                    if (removableRhsMemberTypes.contains(memberTypeRhs)) {
                        removableRhsMemberTypes.remove(memberTypeRhs);
                    }
                }
                if (firstValidOpSymbol == symTable.notFoundSymbol && resultantOpSymbol != symTable.notFoundSymbol) {
                    firstValidOpSymbol = resultantOpSymbol;
                }
            }
            if (!isValidLhsMemberType) {
                removableLhsMemberTypes.add(memberTypeLhs);
            }
        }
        for (BType memberTypeRhs : removableRhsMemberTypes) {
            rhsType.remove(memberTypeRhs);
        }
        for (BType memberTypeLhs : removableLhsMemberTypes) {
            lhsType.remove(memberTypeLhs);
        }
        if (memberTypes.size() != 1) {
            data.resultType = BUnionType.create(null, memberTypes);
        } else if (memberTypes.size() == 1) {
            data.resultType = memberTypes.iterator().next();
        }
        return firstValidOpSymbol;
    }

    private BSymbol getOpSymbolLhsUnion(OperatorKind opKind, BUnionType lhsType, BType rhsType, BLangBinaryExpr binaryExpr, AnalyzerData data, boolean swap) {
        BSymbol firstValidOpSymbol = symTable.notFoundSymbol;
        LinkedHashSet<BType> memberTypes = new LinkedHashSet<>();
        LinkedHashSet<BType> removableLhsMemberTypes = new LinkedHashSet<>();
        for (BType memberTypeLhs : lhsType.getMemberTypes()) {
            boolean isValidLhsMemberType = false;
            BSymbol resultantOpSymbol;
            if (swap) {
                resultantOpSymbol = getOpSymbol(rhsType, memberTypeLhs, binaryExpr, data);
            } else {
                resultantOpSymbol = getOpSymbol(memberTypeLhs, rhsType, binaryExpr, data);
            }
            if (data.resultType != symTable.semanticError) {
                memberTypes.add(data.resultType);
                isValidLhsMemberType = true;
            }
            if (firstValidOpSymbol == symTable.notFoundSymbol && resultantOpSymbol != symTable.notFoundSymbol) {
                firstValidOpSymbol = resultantOpSymbol;
            }

            if (!isValidLhsMemberType) {
                removableLhsMemberTypes.add(memberTypeLhs);
            }
        }
        for (BType memberTypeLhs : removableLhsMemberTypes) {
            lhsType.remove(memberTypeLhs);
        }
        if (memberTypes.size() != 1) {
            data.resultType = BUnionType.create(null, memberTypes);
        } else if (memberTypes.size() == 1) {
            data.resultType = memberTypes.iterator().next();
        }
        return firstValidOpSymbol;
    }

    private BSymbol getOpSymbolBothNonUnion(BType lhsType, BType rhsType, BLangBinaryExpr binaryExpr, AnalyzerData data) {
        return getOpSymbol(lhsType, rhsType, binaryExpr, data);
    }

    private BSymbol getOpSymbol(BType lhsType, BType rhsType, BLangBinaryExpr binaryExpr, AnalyzerData data) {
        BSymbol opSymbol = symResolver.resolveBinaryOperator(binaryExpr.opKind, lhsType, rhsType);
        if (lhsType != symTable.semanticError && rhsType != symTable.semanticError) {
            // Look up operator symbol if both rhs and lhs types aren't error or xml types

            if (opSymbol == symTable.notFoundSymbol) {
                opSymbol = symResolver.getBitwiseShiftOpsForTypeSets(binaryExpr.opKind, lhsType, rhsType);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                opSymbol = symResolver.getBinaryBitwiseOpsForTypeSets(binaryExpr.opKind, lhsType, rhsType);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                opSymbol = symResolver.getArithmeticOpsForTypeSets(binaryExpr.opKind, lhsType, rhsType);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                opSymbol = symResolver.getBinaryEqualityForTypeSets(binaryExpr.opKind, lhsType, rhsType,
                        binaryExpr, data.env);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                opSymbol = symResolver.getBinaryComparisonOpForTypeSets(binaryExpr.opKind, lhsType, rhsType);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                opSymbol = symResolver.getRangeOpsForTypeSets(binaryExpr.opKind, lhsType, rhsType);
            }

            if (opSymbol == symTable.notFoundSymbol) {
                DiagnosticErrorCode errorCode = DiagnosticErrorCode.BINARY_OP_INCOMPATIBLE_TYPES;

                if ((binaryExpr.opKind == OperatorKind.DIV || binaryExpr.opKind == OperatorKind.MOD) &&
                        lhsType.tag == TypeTags.INT &&
                        (rhsType.tag == TypeTags.DECIMAL || rhsType.tag == TypeTags.FLOAT)) {
                    errorCode = DiagnosticErrorCode.BINARY_OP_INCOMPATIBLE_TYPES_INT_FLOAT_DIVISION;
                }

//                dlog.error(binaryExpr.pos, errorCode, binaryExpr.opKind, lhsType, rhsType);
                data.resultType = symTable.semanticError;
            } else {
                data.resultType = opSymbol.type.getReturnType();
            }
        }
        return opSymbol;
    }

    private void logUndefinedSymbolError(Location pos, String name) {
        if (!missingNodesHelper.isMissingNode(name)) {
            dlog.error(pos, DiagnosticErrorCode.UNDEFINED_SYMBOL, name);
        }
    }

    /**
     * @since 2201.4.0
     */
    public static class GetConstantValidType implements TypeVisitor {

        private static final CompilerContext.Key<ConstantTypeChecker.GetConstantValidType> GET_CONSTANT_VALID_TYPE_KEY =
                new CompilerContext.Key<>();

        private final SymbolTable symTable;
        private final Types types;
        private final ConstantTypeChecker constantTypeChecker;
        private final Names names;
        private final ConstantTypeChecker.FillMembers fillMembers;

        private AnalyzerData data;

        public GetConstantValidType(CompilerContext context) {
            context.put(GET_CONSTANT_VALID_TYPE_KEY, this);

            this.symTable = SymbolTable.getInstance(context);
            this.types = Types.getInstance(context);
            this.constantTypeChecker = ConstantTypeChecker.getInstance(context);
            this.names = Names.getInstance(context);
            this.fillMembers = FillMembers.getInstance(context);
        }

        public static ConstantTypeChecker.GetConstantValidType getInstance(CompilerContext context) {
            ConstantTypeChecker.GetConstantValidType getConstantValidType = context.get(GET_CONSTANT_VALID_TYPE_KEY);
            if (getConstantValidType == null) {
                getConstantValidType = new ConstantTypeChecker.GetConstantValidType(context);
            }

            return getConstantValidType;
        }

        public BType getValidType(BType expType, BType type, AnalyzerData data) {
            this.data = data;
            BType preInferredType = data.inferredType;
            data.inferredType = type;
            expType.accept(this);
            data.inferredType = preInferredType;

            return data.resultType;
        }

        @Override
        public void visit(BAnnotationType bAnnotationType) {

        }

        @Override
        public void visit(BArrayType bArrayType) {
            BType inferredType = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(data.inferredType));
            if (inferredType.tag != TypeTags.TUPLE) {
                // Inferred type should be a tuple type (cannot be an array type or any other type).
                data.resultType = symTable.semanticError;
                return;
            }

            BTupleType exprTupleType = (BTupleType) inferredType;
            List<BType> tupleTypes = exprTupleType.getTupleTypes();
            if (bArrayType.state == BArrayState.CLOSED && bArrayType.size < tupleTypes.size()) {
                // Expected type is closed array and has fewer members compared to inferred type.
                data.resultType = symTable.semanticError;
                return;
            }

            BType eType = bArrayType.eType;
            List<BType> validatedMemberTypes = new ArrayList<>(tupleTypes.size());
            for (BType type : tupleTypes) {
                BType validatedMemberType = getValidType(eType, type, data);
                if (validatedMemberType == symTable.semanticError) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                validatedMemberTypes.add(validatedMemberType);
            }

            // Create new tuple type using validated members.
            BTypeSymbol tupleTypeSymbol = Symbols.createTypeSymbol(SymTag.TUPLE_TYPE, Flags.asMask(EnumSet.of(Flag.PUBLIC)),
                    Names.EMPTY, data.env.enclPkg.symbol.pkgID, null,
                    data.env.scope.owner, null, SOURCE);
            List<BTupleMember> members = new ArrayList<>();
            validatedMemberTypes.forEach(m ->
                    members.add(new BTupleMember(m, Symbols.createVarSymbolForTupleMember(m))));
            BTupleType resultTupleType = new BTupleType(tupleTypeSymbol, members);
            tupleTypeSymbol.type = resultTupleType;

            if (bArrayType.state == BArrayState.CLOSED && bArrayType.size > tupleTypes.size()) {
                // Add Fill mambers.
                if (!fillMembers.addFillMembers(resultTupleType, bArrayType, data)) {
                    data.resultType = symTable.semanticError;
                    return;
                }
            }
            data.resultType = resultTupleType;
        }

        @Override
        public void visit(BBuiltInRefType bBuiltInRefType) {

        }

        @Override
        public void visit(BAnyType bAnyType) {

        }

        @Override
        public void visit(BAnydataType bAnydataType) {

        }

        @Override
        public void visit(BErrorType bErrorType) {

        }

        @Override
        public void visit(BFiniteType finiteType) {
            BType inferredType = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(data.inferredType));
            if (inferredType.tag != TypeTags.FINITE && inferredType.tag != TypeTags.UNION) {
                // Inferred type also should a finite type or union of finite types.
                data.resultType = symTable.semanticError;;
                return;
            }

            Set<BLangExpression> valueSpace = finiteType.getValueSpace();
            List<BFiniteType> validFiniteTypes = new ArrayList<>(valueSpace.size());
            for (BLangExpression expr : valueSpace) {
                BFiniteType elementType = new BFiniteType(finiteType.tsymbol);
                elementType.addValue(expr);
                elementType.tsymbol.type = elementType;

                if (inferredType.tag == TypeTags.UNION) {
                    for (BType memberType : ((BUnionType) inferredType).getMemberTypes()) {
                        if (types.isAssignable(memberType, elementType)) {
                            validFiniteTypes.add((BFiniteType) memberType);
                            continue;
                        }
                    }
                } else {
                    if (types.isAssignable(inferredType, elementType)) {
                        validFiniteTypes.add((BFiniteType) inferredType);
                        continue;
                    }
                }
            }
            if (validFiniteTypes.size() == 0) {
                data.resultType = symTable.semanticError;
                return;
            } else if (validFiniteTypes.size() == 1) {
                data.resultType = validFiniteTypes.get(0);
                return;
            }

            BType selectedType = symTable.semanticError;
            for (BFiniteType type: validFiniteTypes) {
                BLangExpression expr = type.getValueSpace().iterator().next();
                BType exprType = expr.getBType();
                if (exprType == symTable.intType) {
                    data.resultType = type;
                    return;
                } else if (exprType == symTable.floatType) {
                    selectedType = exprType;
                } else if (exprType == symTable.decimalType && selectedType == symTable.semanticError) {
                    selectedType = exprType;
                }
            }
            data.resultType = selectedType;
            return;
        }

        @Override
        public void visit(BInvokableType bInvokableType) {

        }

        @Override
        public void visit(BJSONType bjsonType) {

        }

        @Override
        public void visit(BMapType mapType) {
            BType inferredType = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(data.inferredType));
            if (inferredType.tag != TypeTags.RECORD) {
                // Inferred type should be a record type (cannot be a map type or any other type).
                data.resultType =  symTable.semanticError;
                return;
            }

            BRecordTypeSymbol recordSymbol = constantTypeChecker.createRecordTypeSymbol(mapType.tsymbol.pkgID,
                    mapType.tsymbol.pos, VIRTUAL, data);
            BType constraintType = mapType.constraint;
            LinkedHashMap<String, BField> fields = ((BRecordType) inferredType).fields;
            LinkedHashMap<String, BField> validatedFields = new LinkedHashMap<>();
            for (String key : fields.keySet()) {
                // Validate each field.
                BField field = fields.get(key);
                BType validFieldType = getValidType(constraintType, field.type, data);
                if (validFieldType == symTable.semanticError) {
                    data.resultType =  symTable.semanticError;
                    return;
                }
                Name fieldName = names.fromString(key);
//                Set<Flag> flags = new HashSet<>();
//                flags.add(Flag.REQUIRED);
                BVarSymbol fieldSymbol = new BVarSymbol(field.symbol.flags, fieldName, recordSymbol.pkgID , validFieldType,
                        recordSymbol, symTable.builtinPos, VIRTUAL);
                validatedFields.put(key, new BField(fieldName, null, fieldSymbol));
            }

            BRecordType recordType = new BRecordType(recordSymbol);
            recordType.restFieldType = symTable.noType;
            recordType.fields = validatedFields;
            recordSymbol.type = recordType;
            recordType.tsymbol = recordSymbol;
            recordType.sealed = true;
            createTypeDefinition(recordType, this.data.constantSymbol.pos, this.data.env);
            data.resultType = recordType;
        }

        @Override
        public void visit(BStreamType bStreamType) {

        }

        @Override
        public void visit(BTypedescType bTypedescType) {

        }

        @Override
        public void visit(BTypeReferenceType bTypeReferenceType) {
            data.resultType = getValidType(bTypeReferenceType.referredType, data.inferredType, data);
        }

        @Override
        public void visit(BParameterizedType bTypedescType) {

        }

        @Override
        public void visit(BNeverType bNeverType) {

        }

        @Override
        public void visit(BNilType bNilType) {
            if (types.isAssignable(data.inferredType, bNilType)) {
                data.resultType = bNilType;
                return;
            }
            data.resultType = symTable.semanticError;
        }

        @Override
        public void visit(BNoType bNoType) {

        }

        @Override
        public void visit(BPackageType bPackageType) {

        }

        @Override
        public void visit(BStructureType bStructureType) {

        }

        @Override
        public void visit(BTupleType tupleType) {
            BType inferredType = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(data.inferredType));
            if (inferredType.tag != TypeTags.TUPLE) {
                // Inferred type should be a tuple type (cannot be an array type or any other type).
                data.resultType = symTable.semanticError;
                return;
            }

            List<BType> actualTupleTypes = ((BTupleType) inferredType).getTupleTypes();
            List<BType> expTupleTypes = tupleType.getTupleTypes();
            int restTypeCount = actualTupleTypes.size() - expTupleTypes.size(); // Excess member amount in inferred type.
            List<BType> validatedMemberTypes = new ArrayList<>();
            int memberCount = expTupleTypes.size();
            if (restTypeCount < 0) {
                memberCount += restTypeCount;
            }
            for (int i = 0; i < memberCount; i++) {
                BType validatedMemberType = getValidType(expTupleTypes.get(i), actualTupleTypes.get(i), data);
                if (validatedMemberType == symTable.semanticError) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                validatedMemberTypes.add(validatedMemberType);
            }

            BType restType = tupleType.restType;
            if (restType == null & restTypeCount > 0) {
                // expType has fewer amount of members compared to inferred type.
                data.resultType = symTable.semanticError;
                return;
            }

            for (int i = expTupleTypes.size(); i < actualTupleTypes.size(); i++) {
                // Validate the remaining members in inferred tuple against rest type in expType.
                BType validatedMemberType = getValidType(restType, actualTupleTypes.get(i), data);
                if (validatedMemberType == symTable.semanticError) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                validatedMemberTypes.add(validatedMemberType);
            }

            BTypeSymbol tupleTypeSymbol = Symbols.createTypeSymbol(SymTag.TUPLE_TYPE, Flags.asMask(EnumSet.of(Flag.PUBLIC)),
                    Names.EMPTY, data.env.enclPkg.symbol.pkgID, null,
                    data.env.scope.owner, null, SOURCE);
            List<BTupleMember> members = new ArrayList<>();
            validatedMemberTypes.forEach(m ->
                    members.add(new BTupleMember(m, Symbols.createVarSymbolForTupleMember(m))));
            BTupleType resultTupleType = new BTupleType(tupleTypeSymbol, members);
            tupleTypeSymbol.type = resultTupleType;
            if (restTypeCount < 0) {
                // Add FIll members.
                if (!fillMembers.addFillMembers(resultTupleType, tupleType, data)) {
                    data.resultType = symTable.semanticError;
                    return;
                }
            }
            data.resultType = resultTupleType;
        }

        @Override
        public void visit(BUnionType unionType) {
            List<BType> validTypeList = new ArrayList<>();
            DiagnosticCode prevDiagCode = data.diagCode;
            for (BType memberType : unionType.getMemberTypes()) {
                // Validate the inferred type against all the member types in union expType.
                BType resultType = getValidType(memberType, data.inferredType, data);
                if (resultType != symTable.semanticError) {
                    validTypeList.add(resultType);
                    prevDiagCode = data.diagCode;
                } else {
                    data.diagCode = prevDiagCode;
                }
            }

            if (validTypeList.isEmpty()) {
                data.resultType = symTable.semanticError;
                return;
            } else if (validTypeList.size() == 1) {
                data.resultType = validTypeList.get(0);
                return;
            }

            BType selectedType = null;
            for (BType type : validTypeList) {
                if (type.tag == TypeTags.FINITE && ((BFiniteType) type).getValueSpace().size() == 1) {
                    switch (((BFiniteType) type).getValueSpace().iterator().next().getBType().tag) {
                        case TypeTags.INT:
                            data.resultType = type;
                            return;
                        case TypeTags.FLOAT:
                            selectedType = type;
                            break;
                        case TypeTags.DECIMAL:
                            if (selectedType == null) {
                                selectedType = type;
                            } else if (selectedType.tag == TypeTags.DECIMAL) {
                                data.diagCode = DiagnosticErrorCode.AMBIGUOUS_TYPES;
                                data.resultType = validTypeList.get(0);
                                return;
                            }
                            break;
                        default:
                            data.diagCode = DiagnosticErrorCode.AMBIGUOUS_TYPES;
                            data.resultType = validTypeList.get(0);
                            return;
                    }
                    continue;
                }
                data.diagCode = DiagnosticErrorCode.AMBIGUOUS_TYPES;
                data.resultType = validTypeList.get(0);
                return;
            }
            data.resultType = selectedType;
            return;
        }

        @Override
        public void visit(BIntersectionType bIntersectionType) {

        }

        @Override
        public void visit(BXMLType bxmlType) {

        }

        @Override
        public void visit(BTableType bTableType) {

        }

        @Override
        public void visit(BRecordType recordType) {
            LinkedHashMap<String, BField> validatedFields = new LinkedHashMap<>();
            BType referredType = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(data.inferredType));
            if (referredType.tag != TypeTags.RECORD) {
                // Inferred type should be a record type (cannot be a map type or any other type).
                data.resultType = symTable.semanticError;
                return;
            }

            BRecordTypeSymbol recordSymbol = constantTypeChecker.createRecordTypeSymbol(recordType.tsymbol.pkgID,
                    recordType.tsymbol.pos,
                    VIRTUAL, data);
            LinkedHashMap<String, BField> actualFields = new LinkedHashMap<>();
            LinkedHashMap<String, BField> targetFields = recordType.fields;

            for (String key : ((BRecordType) referredType).fields.keySet()) {
                actualFields.put(key, ((BRecordType) referredType).fields.get(key));
            }
            for (String key : targetFields.keySet()) {
                if (!actualFields.containsKey(key)) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                BType validFieldType = getValidType(targetFields.get(key).type, actualFields.get(key).type, data);
                if (validFieldType == symTable.semanticError) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                Name fieldName = names.fromString(key);
                Set<Flag> flags = new HashSet<>();
                flags.add(Flag.REQUIRED);
                BVarSymbol fieldSymbol = new BVarSymbol(Flags.asMask(flags), fieldName, recordSymbol.pkgID , validFieldType,
                        recordSymbol, symTable.builtinPos, VIRTUAL);
                validatedFields.put(key, new BField(fieldName, null, fieldSymbol));
                actualFields.remove(key);
            }

            BType restFieldType = recordType.restFieldType;
            if (actualFields.size() != 0) {
                if (!recordType.sealed) {
                    for (String key : actualFields.keySet()) {
                        BType validFieldType = constantTypeChecker.getNarrowedType(actualFields.get(key).type);
                        if (validFieldType == symTable.semanticError) {
                            data.resultType = symTable.semanticError;
                            return;
                        }
                        Name fieldName = names.fromString(key);
                        Set<Flag> flags = new HashSet<>();
                        flags.add(Flag.REQUIRED);
                        BVarSymbol fieldSymbol = new BVarSymbol(Flags.asMask(flags), fieldName, recordSymbol.pkgID , validFieldType,
                                recordSymbol, symTable.builtinPos, VIRTUAL);
                        validatedFields.put(key, new BField(fieldName, null, fieldSymbol));
                    }
                } else if (restFieldType == null) {
                    data.resultType = symTable.semanticError;
                    return;
                } else {
                    for (String key : actualFields.keySet()) {
                        BType validFieldType = getValidType(restFieldType, actualFields.get(key).type, data);
                        if (validFieldType == symTable.semanticError) {
                            data.resultType = symTable.semanticError;
                            return;
                        }
                        Name fieldName = names.fromString(key);
                        Set<Flag> flags = new HashSet<>();
                        flags.add(Flag.REQUIRED);
                        BVarSymbol fieldSymbol = new BVarSymbol(Flags.asMask(flags), fieldName, recordSymbol.pkgID , validFieldType,
                                recordSymbol, symTable.builtinPos, VIRTUAL);
                        validatedFields.put(key, new BField(fieldName, null, fieldSymbol));
                    }
                }
            }

            // TODO - Handle defaultable fields
            BRecordType type = new BRecordType(recordSymbol);
            type.restFieldType = symTable.noType;
            type.fields = validatedFields;
            recordSymbol.type = type;
            type.tsymbol = recordSymbol;
            type.sealed = true;
            createTypeDefinition(type, this.data.constantSymbol.pos, this.data.env);
            data.resultType = type;
        }

        private void createTypeDefinition(BRecordType type, Location pos, SymbolEnv env) {
            BRecordTypeSymbol recordSymbol = (BRecordTypeSymbol) type.tsymbol;

            BTypeDefinitionSymbol typeDefinitionSymbol = Symbols.createTypeDefinitionSymbol(type.tsymbol.flags,
                    type.tsymbol.name, env.scope.owner.pkgID, null, env.scope.owner, pos, VIRTUAL);
            typeDefinitionSymbol.scope = new Scope(typeDefinitionSymbol);
            typeDefinitionSymbol.scope.define(names.fromString(typeDefinitionSymbol.name.value), typeDefinitionSymbol);

            type.tsymbol.scope = new Scope(type.tsymbol);
            for (BField field : ((HashMap<String, BField>) type.fields).values()) {
                type.tsymbol.scope.define(field.name, field.symbol);
                field.symbol.owner = recordSymbol;
            }
            typeDefinitionSymbol.type = type;
            recordSymbol.type = type;
            recordSymbol.typeDefinitionSymbol = typeDefinitionSymbol;
            recordSymbol.markdownDocumentation = new MarkdownDocAttachment(0);

            BLangRecordTypeNode recordTypeNode = TypeDefBuilderHelper.createRecordTypeNode(new ArrayList<>(), type,
                    pos);
            TypeDefBuilderHelper.populateStructureFields(types, symTable, null, names, recordTypeNode, type, type, pos,
                    env, env.scope.owner.pkgID, null, 0, false);
            recordTypeNode.sealed = true;
            type.restFieldType = new BNoType(TypeTags.NONE);
            BLangTypeDefinition typeDefinition = TypeDefBuilderHelper.createTypeDefinitionForTSymbol(null,
                    typeDefinitionSymbol, recordTypeNode, env);
            typeDefinition.symbol.scope = new Scope(typeDefinition.symbol);
            typeDefinition.symbol.type = type;
            typeDefinition.flagSet = new HashSet<>();
            typeDefinition.flagSet.add(Flag.PUBLIC);
            typeDefinition.flagSet.add(Flag.ANONYMOUS);
        }

        @Override
        public void visit(BObjectType bObjectType) {

        }

        @Override
        public void visit(BType type) {
            switch (type.tag) {
                case TypeTags.INT:
                case TypeTags.SIGNED8_INT:
                case TypeTags.SIGNED16_INT:
                case TypeTags.SIGNED32_INT:
                case TypeTags.UNSIGNED8_INT:
                case TypeTags.UNSIGNED16_INT:
                case TypeTags.UNSIGNED32_INT:
                case TypeTags.BYTE:
                case TypeTags.FLOAT:
                case TypeTags.DECIMAL:
                case TypeTags.STRING:
                case TypeTags.CHAR_STRING:
                case TypeTags.NIL:
                case TypeTags.BOOLEAN:
                    if (data.inferredType.tag == TypeTags.UNION) {
                        for (BType memberType : ((BUnionType) data.inferredType).getMemberTypes()) {
                            if (types.isAssignable(memberType, type)) {
                                if (type.tag == TypeTags.BYTE) {
                                    BFiniteType finiteType = new BFiniteType(memberType.tsymbol);
                                    Object value =
                                            ((BLangNumericLiteral) ((BFiniteType) memberType).getValueSpace().iterator().next()).value;
                                    finiteType.addValue(constantTypeChecker.getLiteral(value, memberType.tsymbol.pos, type));
                                    finiteType.tsymbol.type = finiteType;
                                    data.resultType =  finiteType;
                                    return;
                                }
                                data.resultType =  memberType;
                                return;
                            }
                        }
                    } else {
                        if (types.isAssignable(data.inferredType, type)) {
                            data.resultType = data.inferredType;
                            return;
                        }
                    }
                default:
                    data.resultType = symTable.semanticError;
            }
        }

        @Override
        public void visit(BFutureType bFutureType) {

        }

        @Override
        public void visit(BHandleType bHandleType) {

        }
    }

    /**
     * @since 2201.4.0
     */
    public static class FillMembers implements TypeVisitor {

        private static final CompilerContext.Key<ConstantTypeChecker.FillMembers> FILL_MEMBERS_KEY =
                new CompilerContext.Key<>();

        private final SymbolTable symTable;
        private final Types types;
        private final ConstantTypeChecker constantTypeChecker;
        private final Names names;
        private final BLangDiagnosticLog dlog;

        private AnalyzerData data;

        public FillMembers(CompilerContext context) {
            context.put(FILL_MEMBERS_KEY, this);

            this.symTable = SymbolTable.getInstance(context);
            this.types = Types.getInstance(context);
            this.constantTypeChecker = ConstantTypeChecker.getInstance(context);
            this.names = Names.getInstance(context);
            this.dlog = BLangDiagnosticLog.getInstance(context);;
        }

        public static ConstantTypeChecker.FillMembers getInstance(CompilerContext context) {
            ConstantTypeChecker.FillMembers fillMembers = context.get(FILL_MEMBERS_KEY);
            if (fillMembers == null) {
                fillMembers = new ConstantTypeChecker.FillMembers(context);
            }

            return fillMembers;
        }

        public boolean addFillMembers(BTupleType type, BType expType, AnalyzerData data) {
            BType refType = Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(expType));
            List<BType> tupleTypes = type.getTupleTypes();
            int tupleMemberCount = tupleTypes.size();
            if (refType.tag == TypeTags.ARRAY) {
                BArrayType arrayType = (BArrayType) expType;
                int noOfFillMembers = arrayType.size - tupleMemberCount;
                BType fillMemberType = getFillMembers(arrayType.eType, data);
                if (fillMemberType == symTable.semanticError) {
                    return false;
                }
                for (int i = 0; i < noOfFillMembers; i++) {
                    tupleTypes.add(fillMemberType);
                }
            } else if (refType.tag == TypeTags.TUPLE) {
                List<BType> bTypeList = ((BTupleType) expType).getTupleTypes();
                for (int i = tupleMemberCount; i < bTypeList.size(); i++) {
                    BType fillMemberType = getFillMembers(bTypeList.get(i), data);
                    if (fillMemberType == symTable.semanticError) {
                        return false;
                    }
                    tupleTypes.add(fillMemberType);
                }
            }
            return true;
        }

        public BType getFillMembers(BType type, AnalyzerData data) {
            this.data = data;
            data.resultType = symTable.semanticError;
            type.accept(this);

            if (data.resultType == symTable.semanticError) {
                dlog.error(data.constantSymbol.pos, DiagnosticErrorCode.INVALID_LIST_CONSTRUCTOR_ELEMENT_TYPE,
                        data.expType);
            }
            return data.resultType;
        }

        @Override
        public void visit(BAnnotationType bAnnotationType) {

        }

        @Override
        public void visit(BArrayType arrayType) {
            BTypeSymbol tupleTypeSymbol = Symbols.createTypeSymbol(SymTag.TUPLE_TYPE, Flags.asMask(EnumSet.of(Flag.PUBLIC)),
                    Names.EMPTY, data.env.enclPkg.symbol.pkgID, null,
                    data.env.scope.owner, null, SOURCE);
            if (arrayType.state == BArrayState.OPEN) {
                BTupleType resultTupleType = new BTupleType(tupleTypeSymbol, new ArrayList<>());
                tupleTypeSymbol.type = resultTupleType;
                data.resultType = resultTupleType;
                return;
            } else if (arrayType.state == BArrayState.INFERRED) {
                data.resultType = symTable.semanticError;
                return;
            }
            BType fillMemberType = getFillMembers(arrayType.eType, data);
            if (fillMemberType == symTable.semanticError) {
                data.resultType = symTable.semanticError;
                return;
            }
            List<BType> tupleTypes = new ArrayList<>(arrayType.size);
            for (int i = 0; i < arrayType.size; i++) {
                tupleTypes.add(fillMemberType);
            }
            List<BTupleMember> members = new ArrayList<>();
            tupleTypes.forEach(m ->
                    members.add(new BTupleMember(m, Symbols.createVarSymbolForTupleMember(m))));
            BTupleType resultTupleType = new BTupleType(tupleTypeSymbol, members);
            tupleTypeSymbol.type = resultTupleType;
            data.resultType = resultTupleType;
        }

        @Override
        public void visit(BBuiltInRefType bBuiltInRefType) {

        }

        @Override
        public void visit(BAnyType bAnyType) {
            data.resultType = symTable.nilType;
        }

        @Override
        public void visit(BAnydataType bAnydataType) {
            data.resultType = symTable.nilType;
        }

        @Override
        public void visit(BErrorType bErrorType) {

        }

        @Override
        public void visit(BFiniteType finiteType) {
            Set<BLangExpression> valueSpace = finiteType.getValueSpace();
            if (valueSpace.size() > 1) {
                if (finiteType.isNullable()) { // Ex. 1|null
                    data.resultType = symTable.nilType;
                    return;
                }
                data.resultType = symTable.semanticError;
                return;
            }
            if (finiteType.isNullable()) {
                // Compiler takes () as nilType and null as a finite ().
                // Added this logic to keep the consistency.
                data.resultType = symTable.nilType;
                return;
            }
            data.resultType = finiteType;
        }

        @Override
        public void visit(BInvokableType bInvokableType) {

        }

        @Override
        public void visit(BJSONType bjsonType) {
            data.resultType = symTable.nilType;
        }

        @Override
        public void visit(BMapType bMapType) {
            data.resultType = symTable.mapType;
        }

        @Override
        public void visit(BStreamType bStreamType) {

        }

        @Override
        public void visit(BTypedescType bTypedescType) {

        }

        @Override
        public void visit(BTypeReferenceType bTypeReferenceType) {

        }

        @Override
        public void visit(BParameterizedType bTypedescType) {

        }

        @Override
        public void visit(BNeverType bNeverType) {

        }

        @Override
        public void visit(BNilType bNilType) {
            data.resultType = symTable.nilType;
        }

        @Override
        public void visit(BNoType bNoType) {

        }

        @Override
        public void visit(BPackageType bPackageType) {

        }

        @Override
        public void visit(BStructureType bStructureType) {

        }

        @Override
        public void visit(BTupleType tupleType) {
            List<BType> bTypeList = tupleType.getTupleTypes();
            BTypeSymbol tupleTypeSymbol = Symbols.createTypeSymbol(SymTag.TUPLE_TYPE, Flags.asMask(EnumSet.of(Flag.PUBLIC)),
                    Names.EMPTY, data.env.enclPkg.symbol.pkgID, null,
                    data.env.scope.owner, null, SOURCE);
            List<BType> tupleTypes = new ArrayList<>(bTypeList.size());
            for (int i = 0; i < bTypeList.size(); i++) {
                BType fillMemberType = getFillMembers(bTypeList.get(i), data);
                if (fillMemberType == symTable.semanticError) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                tupleTypes.add(fillMemberType);
            }
            List<BTupleMember> members = new ArrayList<>();
            tupleTypes.forEach(m ->
                    members.add(new BTupleMember(m, Symbols.createVarSymbolForTupleMember(m))));
            BTupleType resultTupleType = new BTupleType(tupleTypeSymbol, members);
            tupleTypeSymbol.type = resultTupleType;
            data.resultType = resultTupleType;
            return;
        }

        @Override
        public void visit(BUnionType unionType) {
            LinkedHashSet<BType> memberTypes = unionType.getMemberTypes();
            if (memberTypes.size() == 1) {
                getFillMembers(memberTypes.iterator().next(), data);
                return;
            }
            if (memberTypes.size() > 2 || !unionType.isNullable()) {
                data.resultType = symTable.semanticError;
                return;
            }
            data.resultType = symTable.nilType;
        }

        @Override
        public void visit(BIntersectionType intersectionType) {
            data.resultType = getFillMembers(intersectionType.getEffectiveType(), data);
        }

        @Override
        public void visit(BXMLType bxmlType) {

        }

        @Override
        public void visit(BTableType bTableType) {
            data.resultType = symTable.tableType;
        }

        @Override
        public void visit(BRecordType recordType) {
            LinkedHashMap<String, BField> fields = recordType.fields;
            BRecordTypeSymbol recordSymbol = constantTypeChecker.createRecordTypeSymbol(data.constantSymbol.pkgID,
                    data.constantSymbol.pos, VIRTUAL, data);
            LinkedHashMap<String, BField> newFields = new LinkedHashMap<>();
            for (String key : fields.keySet()) {
                BField field = fields.get(key);
                if ((field.symbol.flags & Flags.REQUIRED) != Flags.REQUIRED) {
                    continue;
                }
                BType fillMemberType = getFillMembers(fields.get(key).type, data);
                if (fillMemberType == symTable.semanticError) {
                    data.resultType = symTable.semanticError;
                    return;
                }
                Name fieldName = names.fromString(key);
                Set<Flag> flags = new HashSet<>();
                flags.add(Flag.REQUIRED);
                BVarSymbol fieldSymbol = new BVarSymbol(Flags.asMask(flags), fieldName, recordSymbol.pkgID , fillMemberType,
                        recordSymbol, symTable.builtinPos, VIRTUAL);
                newFields.put(key, new BField(fieldName, null, fieldSymbol));
            }
            BRecordType resultRecordType = new BRecordType(recordSymbol);
            resultRecordType.fields = newFields;
            recordSymbol.type = resultRecordType;
            resultRecordType.tsymbol = recordSymbol;
            resultRecordType.sealed = true;
            data.resultType = resultRecordType;
        }

        @Override
        public void visit(BObjectType bObjectType) {

        }

        @Override
        public void visit(BType type) {
            BType refType = Types.getReferredType(type);
            switch (refType.tag) {
                case TypeTags.BOOLEAN:
                    data.resultType = symTable.falseType;
                    return;
                case TypeTags.INT:
                case TypeTags.SIGNED8_INT:
                case TypeTags.SIGNED16_INT:
                case TypeTags.SIGNED32_INT:
                case TypeTags.UNSIGNED8_INT:
                case TypeTags.UNSIGNED16_INT:
                case TypeTags.UNSIGNED32_INT:
                case TypeTags.BYTE:
                    data.resultType = constantTypeChecker.getFiniteType(0l, data.constantSymbol, null, symTable.intType);
                    return;
                case TypeTags.FLOAT:
                    data.resultType = constantTypeChecker.getFiniteType(0.0d, data.constantSymbol, null, symTable.floatType);
                    return;
                case TypeTags.DECIMAL:
                    data.resultType = constantTypeChecker.getFiniteType(new BigDecimal(0), data.constantSymbol, null, symTable.decimalType);
                    return;
                case TypeTags.STRING:
                case TypeTags.CHAR_STRING:
                    data.resultType = constantTypeChecker.getFiniteType("", data.constantSymbol, null, symTable.stringType);
                    return;
                default:
                    data.resultType = symTable.semanticError;
            }
        }

        @Override
        public void visit(BFutureType bFutureType) {

        }

        @Override
        public void visit(BHandleType bHandleType) {

        }
    }
    
    public BType getNarrowedType(BType type) {
        switch (type.tag) {
            case TypeTags.FINITE:
                return type;
            case TypeTags.UNION:
                return ((BUnionType) type).getMemberTypes().iterator().next();
            case TypeTags.RECORD:
                for (String key : ((BRecordType) type).fields.keySet()) {
                    BField field = ((BRecordType) type).fields.get(key);
                    field.type = getNarrowedType(field.type);
                }
                return type;
            case TypeTags.ARRAY:
                ((BArrayType) type).eType = getNarrowedType(((BArrayType) type).eType);
                return type;
            case TypeTags.TUPLE:
                List<BType> tupleTypes = ((BTupleType) type).getTupleTypes();
                List<BType> newTupleTypes = new ArrayList<>(tupleTypes.size());
                for (BType memberType : tupleTypes) {
                    newTupleTypes.add(getNarrowedType(memberType));
                }
                List<BTupleMember> members = new ArrayList<>();
                newTupleTypes.forEach(m ->
                        members.add(new BTupleMember(m, Symbols.createVarSymbolForTupleMember(m))));
                ((BTupleType) type).setMembers(members);
                return type;
            default:
                return symTable.semanticError;
        }
    }

    public BLangConstantValue getConstantValue(BType type) {
        // Obtain the constant value using its type.
        switch (type.tag) {
            case TypeTags.FINITE:
                BLangExpression expr = ((BFiniteType) type).getValueSpace().iterator().next();
                if (expr.getBType().tag == TypeTags.DECIMAL) {
                    return new BLangConstantValue ((((BLangNumericLiteral) expr).value).toString(), expr.getBType());
                }
                return new BLangConstantValue (((BLangLiteral) expr).value, expr.getBType());
            case TypeTags.INTERSECTION:
                return getConstantValue(((BIntersectionType) type).getEffectiveType());
            case TypeTags.RECORD:
                Map<String, BLangConstantValue> fields = new HashMap<>();
                LinkedHashMap<String, BField> recordFields = ((BRecordType) type).fields;
                for (String key : recordFields.keySet()) {
                    BLangConstantValue constantValue = getConstantValue(recordFields.get(key).type);
                    fields.put(key, constantValue);
                }
                return new BLangConstantValue(fields, ((BRecordType) type).getIntersectionType().get());
            case TypeTags.TUPLE:
                List<BLangConstantValue> members = new ArrayList<>();
                List<BType> tupleTypes = ((BTupleType) type).getTupleTypes();
                for (BType memberType : tupleTypes) {
                    BLangConstantValue constantValue = getConstantValue(memberType);
                    members.add(constantValue);
                }
                return new BLangConstantValue(members, type);
            case TypeTags.NIL:
                return new BLangConstantValue (type.tsymbol.getType().toString(), type.tsymbol.getType());
            default:
                return null;
        }
    }

    /**
     * @since 2201.4.0
     */
    public static class ResolveConstantExpressionType extends SimpleBLangNodeAnalyzer<ConstantTypeChecker.AnalyzerData> {

        private static final CompilerContext.Key<ConstantTypeChecker.ResolveConstantExpressionType>
                RESOLVE_CONSTANT_EXPRESSION_TYPE = new CompilerContext.Key<>();

        private final SymbolTable symTable;
        private final Types types;
        private final ConstantTypeChecker constantTypeChecker;

        private List<BLangTypeDefinition> resolvingtypeDefinitions = new ArrayList<>();

        public ResolveConstantExpressionType(CompilerContext context) {
            context.put(RESOLVE_CONSTANT_EXPRESSION_TYPE, this);

            this.symTable = SymbolTable.getInstance(context);
            this.types = Types.getInstance(context);
            this.constantTypeChecker = ConstantTypeChecker.getInstance(context);
        }

        public static ResolveConstantExpressionType getInstance(CompilerContext context) {
            ResolveConstantExpressionType resolveConstantExpressionType = context.get(RESOLVE_CONSTANT_EXPRESSION_TYPE);
            if (resolveConstantExpressionType == null) {
                resolveConstantExpressionType = new ResolveConstantExpressionType(context);
            }

            return resolveConstantExpressionType;
        }

        public BType resolveConstExpr(BLangExpression expr, BType expType, AnalyzerData data) {
            return resolveConstExpr(expr, data.env, expType, DiagnosticErrorCode.INCOMPATIBLE_TYPES, data);
        }

        public BType resolveConstExpr(BLangExpression expr, SymbolEnv env, BType expType, DiagnosticCode diagCode,
                                    AnalyzerData data) {

            SymbolEnv prevEnv = data.env;
            BType preExpType = data.expType;
            DiagnosticCode preDiagCode = data.diagCode;
            data.env = env;
            data.diagCode = diagCode;
            if (expType.tag == TypeTags.INTERSECTION) {
                data.expType = ((BIntersectionType) expType).effectiveType;
            } else {
                data.expType = expType;
            }

            expr.expectedType = expType;

            expr.accept(this, data);

            data.env = prevEnv;
            data.expType = preExpType;
            data.diagCode = preDiagCode;

            return data.resultType;
        }

        @Override
        public void analyzeNode(BLangNode node, AnalyzerData data) {

        }

        @Override
        public void visit(BLangPackage node, AnalyzerData data) {

        }

        @Override
        public void visit(BLangLiteral literalExpr, AnalyzerData data) {
            updateBlangExprType(literalExpr, data);
        }

        private void updateBlangExprType(BLangExpression expression, AnalyzerData data) {
            BType expressionType = expression.getBType();
            if (expressionType.tag == TypeTags.FINITE) {
                expressionType = ((BFiniteType) expressionType).getValueSpace().iterator().next().getBType();
                expression.setBType(expressionType);
                types.setImplicitCastExpr(expression, data.expType, expressionType);
                return;
            }
            if (expressionType.tag != TypeTags.UNION) {
                return;
            }

            BType targetType = symTable.noType;
            BType expType = data.expType;
            if (expType.tag == TypeTags.FINITE) {
                targetType = ((BFiniteType) expType).getValueSpace().iterator().next().getBType();
            } else {
                targetType = expType;
            }

            for (BType memberType : ((BUnionType) expressionType).getMemberTypes()) {
                BType type = ((BFiniteType) memberType).getValueSpace().iterator().next().getBType();

                if (type.tag == targetType.tag || types.isAssignable(memberType, targetType)) {
                    expression.setBType(type);
                    types.setImplicitCastExpr(expression, type, memberType);
                    return;
                }
            }
        }

        @Override
        public void visit(BLangSimpleVarRef varRefExpr, AnalyzerData data) {

        }

        public void visit(BLangListConstructorExpr listConstructor, AnalyzerData data) {
            BType resolvedType = data.expType;
            BTupleType tupleType = (BTupleType) ((resolvedType.tag == TypeTags.INTERSECTION)?
                    ((BIntersectionType) resolvedType).effectiveType : resolvedType);
            List<BType> resolvedMemberType = tupleType.getTupleTypes();
            listConstructor.setBType(data.expType);
            int currentListIndex = 0;
            for (BLangExpression memberExpr : listConstructor.exprs) {
                if (memberExpr.getKind() == NodeKind.LIST_CONSTRUCTOR_SPREAD_OP) {
                    BLangListConstructorExpr.BLangListConstructorSpreadOpExpr spreadOp = (BLangListConstructorExpr.BLangListConstructorSpreadOpExpr) memberExpr;
                    BTupleType type = (BTupleType) Types.getReferredType(types.getTypeWithEffectiveIntersectionTypes(spreadOp.expr.getBType()));
                    spreadOp.setBType(spreadOp.expr.getBType());
                    currentListIndex += type.getTupleTypes().size();
                    continue;
                }
                resolveConstExpr(memberExpr, resolvedMemberType.get(currentListIndex), data);
                currentListIndex++;
            }
        }

        public void visit(BLangRecordLiteral recordLiteral, AnalyzerData data) {
            BType expFieldType;
            BType resolvedType = data.expType;
            recordLiteral.setBType(data.expType);
            for (RecordLiteralNode.RecordField field : recordLiteral.fields) {
                if (field.isKeyValueField()) {
                    BLangRecordLiteral.BLangRecordKeyValueField keyValue =
                            (BLangRecordLiteral.BLangRecordKeyValueField) field;
                    BLangRecordLiteral.BLangRecordKey key = keyValue.key;
                    if (key.computedKey) {
                        BLangRecordLiteral.BLangRecordKeyValueField computedKeyValue = (BLangRecordLiteral.BLangRecordKeyValueField) field;
                        BLangRecordLiteral.BLangRecordKey computedKey = computedKeyValue.key;
                        BType fieldName = constantTypeChecker.checkConstExpr(computedKey.expr, data);
                        BLangLiteral fieldNameLiteral = (BLangLiteral) ((BFiniteType) fieldName).getValueSpace().iterator().next();
                        expFieldType = getResolvedFieldType(constantTypeChecker.getKeyName(fieldNameLiteral), resolvedType);
                        resolveConstExpr(computedKey.expr, expFieldType, data);
                        continue;
                    }
                    BLangExpression keyValueExpr = keyValue.valueExpr;
                    expFieldType = getResolvedFieldType(constantTypeChecker.getKeyName(key.expr), resolvedType);
                    resolveConstExpr(keyValueExpr, expFieldType, data);
                } else if (field.getKind() == NodeKind.SIMPLE_VARIABLE_REF) {
                    BLangRecordLiteral.BLangRecordVarNameField varNameField =
                            (BLangRecordLiteral.BLangRecordVarNameField) field;
                    expFieldType = getResolvedFieldType(constantTypeChecker.getKeyName(varNameField), resolvedType);
                    resolveConstExpr(varNameField, expFieldType, data);
                } else {
                    // Spread Field
                    // Spread fields are not required to resolve separately since they are constant references.
                    BLangRecordLiteral.BLangRecordSpreadOperatorField spreadField =
                            (BLangRecordLiteral.BLangRecordSpreadOperatorField) field;
                    spreadField.setBType(spreadField.expr.getBType());
                }
            }
        }

        private BType getResolvedFieldType(Object targetKey, BType resolvedType) {
            BRecordType recordType = (BRecordType) ((resolvedType.tag == TypeTags.INTERSECTION)?
                    ((BIntersectionType) resolvedType).effectiveType : resolvedType);
            for (String key : recordType.getFields().keySet()) {
                if (key.equals(targetKey)) {
                    BType type = recordType.getFields().get(key).type;
//                    if (type.tag == TypeTags.FINITE) {
//                        return ((BFiniteType) type).getValueSpace().iterator().next().getBType();
//                    }
                    return type;
                }
            }
            return null;
        }

        @Override
        public void visit(BLangBinaryExpr binaryExpr, AnalyzerData data) {
            switch (binaryExpr.opKind) {
                case OR:
                case AND:
                case ADD:
                case SUB:
                case MUL:
                case DIV:
                case MOD:
                    resolveConstExpr(binaryExpr.lhsExpr, data.expType, data);
                    resolveConstExpr(binaryExpr.rhsExpr, data.expType, data);
                    updateBlangExprType(binaryExpr, data);
                    BInvokableType invokableType = (BInvokableType) binaryExpr.opSymbol.type;
                    ArrayList<BType> paramTypes = new ArrayList<>(2);
                    paramTypes.add(binaryExpr.lhsExpr.getBType());
                    paramTypes.add(binaryExpr.rhsExpr.getBType());
                    invokableType.paramTypes = paramTypes;
                    invokableType.retType = binaryExpr.getBType();
                    return;
            }
        }

        public void visit(BLangUnaryExpr unaryExpr, AnalyzerData data) {
            updateBlangExprType(unaryExpr.expr, data);
            updateBlangExprType(unaryExpr, data);
            BInvokableType invokableType = (BInvokableType) unaryExpr.opSymbol.type;
            ArrayList<BType> paramTypes = new ArrayList<>(1);
            paramTypes.add(unaryExpr.expr.getBType());
            invokableType.paramTypes = paramTypes;
            invokableType.retType = unaryExpr.getBType();
        }

        public void visit(BLangGroupExpr groupExpr, AnalyzerData data) {
            updateBlangExprType(groupExpr.expression, data);
            updateBlangExprType(groupExpr, data);
        }
    }

    /**
     * @since 2201.4.0
     */
    public static class AnalyzerData extends TypeChecker.AnalyzerData {
        public SymbolEnv env;
        boolean isTypeChecked;
        Stack<SymbolEnv> prevEnvs;
        Types.CommonAnalyzerData commonAnalyzerData = new Types.CommonAnalyzerData();
        DiagnosticCode diagCode;
        BType expType;
        BType inferredType;
        BType resultType;
        Map<String, BLangNode> modTable;
        BConstantSymbol constantSymbol;
    }
}
