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

public enum IssueType {
   BUG,
   DEPENDENCY_UPGRADE,
   IMPROVEMENT,
   NEW_FEATURE,
   TASK;

   public static IssueType fromName(String type) {
      switch (type) {
         case "Bug":
            return IssueType.BUG;
         case "Dependency upgrade":
            return IssueType.DEPENDENCY_UPGRADE;
         case "Improvement":
            return IssueType.IMPROVEMENT;
         case "Enhancement":
         case "New Feature":
         case "Wish":
         case "Test":
         case "Epic":
            return IssueType.NEW_FEATURE;
         case "Task":
         case "Sub-task":
            return IssueType.TASK;
         default:
            throw new IllegalStateException("Invalid type: " + type);
      }
   }

   public static String toName(IssueType type) {
      switch (type) {
         case BUG:
            return "Bug";
         case IMPROVEMENT:
            return "Enhancement";
         default:
            throw new IllegalStateException("Invalid type: " + type);
      }
   }
}
