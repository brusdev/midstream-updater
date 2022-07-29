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

package com.redhat.midstream.updater;

public class CommitTask {
   private CommitTaskType type;
   private CommitTaskState state;
   private String key;
   private String value;
   private String assignee;
   private String result;

   public String getAssignee() {
      return assignee;
   }

   public CommitTask setAssignee(String assignee) {
      this.assignee = assignee;
      return this;
   }

   public CommitTaskType getType() {
      return type;
   }

   public CommitTask setType(CommitTaskType type) {
      this.type = type;
      return this;
   }

   public String getValue() {
      return value;
   }

   public CommitTask setValue(String value) {
      this.value = value;
      return this;
   }

   public String getKey() {
      return key;
   }

   public CommitTask setKey(String key) {
      this.key = key;
      return this;
   }

   public CommitTaskState getState() {
      return state;
   }

   public CommitTask setState(CommitTaskState state) {
      this.state = state;
      return this;
   }

   public String getResult() {
      return result;
   }

   public CommitTask setResult(String result) {
      this.result = result;
      return this;
   }
}
