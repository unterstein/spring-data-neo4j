/*
 * Copyright (c)  [2011-2015] "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.domain.convertible.numbers;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * @author Vince Bickers
 */
public class Account {

    private Long id;
    private BigDecimal balance;
    private BigInteger facility;

    public Account(BigDecimal balance, BigInteger facility) {
        this.balance = balance;
        this.facility = facility;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigInteger getFacility() {
        return facility;
    }

    public void setFacility(BigInteger facility) {
        this.facility = facility;
    }
}