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

public enum IssueState {
   NEW,
   TODO,
   CLOSED,
   IN_PROGRESS,
   BLOCKED,
   REOPENED,
   READY_FOR_REVIEW

   ;


   public static IssueState fromName(String state) {
      switch (state) {
         case "New":
         case "Open":
            return IssueState.NEW;
         case "Reopened":
            return IssueState.REOPENED;
         case "Closed":
         case "Resolved":
            return IssueState.CLOSED;
         case "In Progress":
            return IssueState.IN_PROGRESS;
         case "Blocked":
            return IssueState.BLOCKED;
         case "Ready for Review":
            return IssueState.READY_FOR_REVIEW;
         case "To Do":
            return IssueState.TODO;
         default:
            throw new IllegalStateException("Invalid type: " + state);
      }
   }

}
