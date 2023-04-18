// Copyright (c) 2023 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/lang.array;

function testSeqVarAsRequiredArg() {
    var input = [{name: "Saman", price: 11}, {name: "Kamal", price: 15}];
    var res = from var {name, price} in input
                group by name
                select array:length(price); // error
}

function testSeqVarInInvalidPositions() {
    var res1 = from var {name, price} in [{name: "Saman", price: 11}, {name: "Kamal", price: 15}]
                group by name
                select price; // error
    int[] res2 = from var {name, price} in [{name: "Saman", price: 11}, {name: "Kamal", price: 15}]
                    group by name
                    select price; // error
    res1 = from var {name, price} in [{name: "Saman", price: 11}, {name: "Kamal", price: 15}]
            group by name
            let var x = price // error
            select x;
    res1 = from var {name, price} in [{name: "Saman", price: 11}, {name: "Kamal", price: 15}]
            group by name
            select price + 23; // error
    var res3 = from var {name, price1, price2} in [{name: "Saman", price1: 11, price2: 12}, {name: "Kamal", price1: 15, price2: 16}]
                group by name
                select [price1, price2]; // error
    var res4 = from var {name, price1, price2} in [{name: "Saman", price1: 11, price2: 12}, {name: "Kamal", price1: 15, price2: 16}]
                group by name
                select [(price2)]; // error
}

// function testSeqVariableInGroupExpr() {
//     var input = [{name: "Saman", price: 11}, {name: "Saman", price: 12}, {name: "Kamal", price: 11}];
//     var prices = from var {name, price} in input
//                         group by name
//                         let var p = [(price)] // error: group-expr is not supported
//                         select p;
// }

// function testGroupbyExpressionAndSelectWithNonGroupingKeys4() {
//     var input = [{name: "Saman", price: 11}, {name: "Saman", price: 12}, {name: "Kamal", price: 11}];
//     int[][][] names = from var {name, price} in input
//                         group by name
//                         select [([price])]; // @output [[[11, 11]], [[12]]]
// }

// function testGroupbyExpressionAndSelectWithNonGroupingKeys4() {
//     var input = [{name: "Saman", price: 11}, {name: "Saman", price: 12}, {name: "Kamal", price: 11}];
//     int[][][] names = from var {name, price} in input
//                         group by name
//                         select [([price])]; // @output [[[11, 11]], [[12]]]
// }

// function testGroupbyExpressionAndSelectWithNonGroupingKeys5() {
//     var input = [{name: "Saman", price: 11}, {name: "Saman", price: 12}, {name: "Kamal", price: 11}];
//     int[][][] names = from var {name, price} in input
//                         group by name
//                         select [[((price))]]; // @output [[[11, 11]], [[12]]]
// }

// function testGroupbyExpressionAndSelectWithNonGroupingKeys6() {
//     var input = [{name: "Saman", price: 11}, {name: "Saman", price: 12}, {name: "Kamal", price: 11}];
//     int[][][] names = from var {name, price} in input
//                         group by name
//                         select ([([price])]); // @output [[[11, 11]], [[12]]]
// }

// function testGroupByExpressionAndSelectWithNonGroupingKeys9() {
//     var input = [{name: "Saman", price: 11}, {name: "Saman", price: 12}, {name: "Kamal", price: 11}];
//     [int, int...][] prices = from var {name, price} in input
//                             group by name
//                             select [price]; // @output [[11, 12], [11]]
//     assertEquality([[11, 12], [11]], prices);
// }
