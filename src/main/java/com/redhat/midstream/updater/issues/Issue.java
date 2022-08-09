/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.midstream.updater.issues;

import java.util.ArrayList;
import java.util.List;

import com.redhat.midstream.updater.CustomerPriority;
import com.redhat.midstream.updater.SecurityImpact;

public class Issue {
   private String key;
   private String creator;
   private String assignee;
   private String reporter;
   private IssueState state;
   private IssueType type;
   private String summary;
   private String description;
   private List<String> labels;
   private List<String> issues;

   private boolean customer;
   private CustomerPriority customerPriority;
   private boolean patch;
   private boolean security;
   private SecurityImpact securityImpact;
   private String targetRelease;

   public String getDescription() {
      return description;
   }

   public Issue setDescription(String description) {
      this.description = description;
      return this;
   }

   public String getAssignee() {
      return assignee;
   }

   public Issue setAssignee(String assignee) {
      this.assignee = assignee;
      return this;
   }

   public String getReporter() {
      return reporter;
   }

   public Issue setReporter(String reporter) {
      this.reporter = reporter;
      return this;
   }

   public String getTargetRelease() {
      return targetRelease;
   }

   public Issue setTargetRelease(String targetRelease) {
      this.targetRelease = targetRelease;
      return this;
   }

   public boolean isCustomer() {
      return customer;
   }

   public Issue setCustomer(boolean customer) {
      this.customer = customer;
      return this;
   }

   public boolean isPatch() {
      return patch;
   }

   public Issue setPatch(boolean patch) {
      this.patch = patch;
      return this;
   }

   public boolean isSecurity() {
      return security;
   }

   public Issue setSecurity(boolean security) {
      this.security = security;
      return this;
   }

   public CustomerPriority getCustomerPriority() {
      return customerPriority;
   }

   public Issue setCustomerPriority(CustomerPriority customerPriority) {
      this.customerPriority = customerPriority;
      return this;
   }

   public SecurityImpact getSecurityImpact() {
      return securityImpact;
   }

   public Issue setSecurityImpact(SecurityImpact securityImpact) {
      this.securityImpact = securityImpact;
      return this;
   }

   public String getCreator() {
      return creator;
   }

   public Issue setCreator(String creator) {
      this.creator = creator;
      return this;
   }

   public IssueState getState() {
      return state;
   }

   public Issue setState(IssueState state) {
      this.state = state;
      return this;
   }

   public Issue() {
      issues = new ArrayList<>();
      labels = new ArrayList<>();
   }

   public List<String> getIssues() {
      return issues;
   }

   public List<String> getLabels() {
      return labels;
   }

   public String getKey() {
      return key;
   }

   public Issue setKey(String key) {
      this.key = key;
      return this;
   }

   public IssueType getType() {
      return type;
   }

   public Issue setType(IssueType type) {
      this.type = type;
      return this;
   }

   public String getSummary() {
      return summary;
   }

   public Issue setSummary(String summary) {
      this.summary = summary;
      return this;
   }
}
