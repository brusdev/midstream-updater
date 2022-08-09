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

import java.io.File;
import java.util.Collection;
import java.util.List;

public interface IssueManager {

   void loadIssues(boolean parseCustomFields) throws Exception;

   void loadIssues(File file) throws Exception;

   Issue getIssue(String key);

   Collection<Issue> getIssues();

   void storeIssues(File file) throws Exception;

   void addIssueLabels(String issueKey, String... labels) throws Exception;

   void setIssueTargetRelease(String issueKey, String targetRelease) throws Exception;

   void transitionIssue(String issueKey, IssueState finalStatus) throws Exception;

   Issue createIssue(String summary, String description, IssueType type, String assignee, String upstreamIssue, String targetRelease, List<String> labels) throws Exception;

   void linkIssue(String issueKey, String cloningIssueKey, String linkType) throws Exception;
}
