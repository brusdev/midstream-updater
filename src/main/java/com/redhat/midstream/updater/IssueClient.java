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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IssueClient {
   private final static Logger logger = LoggerFactory.getLogger(IssueClient.class);

   //"id":"customfield_12311240","name":"Target Release"
   private final static String TARGET_RELEASE_FIELD = "customfield_12311240";

   private final static Pattern upstreamIssuePattern = Pattern.compile("ARTEMIS-[0-9]+");
   private final static Pattern securityImpactPattern = Pattern.compile("Impact: (Critical|Important|Moderate|Low)");

   private String serverURL;
   private String authString;

   public IssueClient(String serverURL, String authString) {
      this.serverURL = serverURL;
      this.authString = authString;
   }

   public Issue createIssue(String projectKey, String summary, String description, IssueType type, String assignee, String upstreamIssue, String targetRelease, List<String> labels) throws Exception {

      JsonObject issueObject = new JsonObject();
      {
         JsonObject fieldsObject = new JsonObject();
         JsonObject projectObject = new JsonObject();
         projectObject.addProperty("key", projectKey);
         fieldsObject.add("project", projectObject);
         JsonObject issueTypeObject = new JsonObject();
         issueTypeObject.addProperty("name", IssueType.toName(type));
         fieldsObject.add("issuetype", issueTypeObject);
         fieldsObject.addProperty("summary", summary);
         if (description != null) {
            fieldsObject.addProperty("description", description);
         }
         //"id":"customfield_12314640","name":"Upstream Jira"
         fieldsObject.addProperty("customfield_12314640", upstreamIssue);
         JsonObject targetReleaseObject = new JsonObject();
         targetReleaseObject.addProperty("name", targetRelease);
         fieldsObject.add(TARGET_RELEASE_FIELD, targetReleaseObject);
         JsonObject assigneeObject = new JsonObject();
         assigneeObject.addProperty("name", assignee);
         fieldsObject.add("assignee", assigneeObject);
         JsonArray labelsArray = new JsonArray();
         for (String label : labels) {
            labelsArray.add(label);
         }
         fieldsObject.add("labels", labelsArray);
         issueObject.add("fields", fieldsObject);
      }

      String issueKey = postIssue(issueObject);

      return parseIssue(getIssue(issueKey), true);
   }

   public Issue cloneIssue(String projectKey, String cloningIssueKey, String targetRelease) throws Exception {
      JsonObject issueObject = getIssue(cloningIssueKey);
      {
         JsonObject fieldsObject = issueObject.getAsJsonObject("fields");

         // Set target release
         fieldsObject.remove(TARGET_RELEASE_FIELD);
         JsonObject targetReleaseObject = new JsonObject();
         targetReleaseObject.addProperty("name", targetRelease);
         fieldsObject.add(TARGET_RELEASE_FIELD, targetReleaseObject);

         JsonArray issueLinksObject = issueObject.getAsJsonArray("issuelinks");

         // Add cloned issue link
         JsonObject issueLinkObject = new JsonObject();
         JsonObject issueLinkTypeObject = new JsonObject();
         issueLinkTypeObject.addProperty("name", "Cloners");
         issueLinkObject.add("type", issueLinkTypeObject);
         JsonObject outwardIssue = new JsonObject();
         outwardIssue.addProperty("key", cloningIssueKey);
         issueLinkObject.add("outwardIssue", outwardIssue);
         fieldsObject.add(TARGET_RELEASE_FIELD, targetReleaseObject);
         issueLinksObject.add(issueLinkObject);
      }

      String issueKey = postIssue(issueObject);

      return parseIssue(getIssue(issueKey), true);
   }

   private String postIssue(JsonObject issueObject) throws Exception {
      HttpURLConnection connection = createConnection("/issue/");
      try {
         connection.setDoOutput(true);
         connection.setRequestMethod("POST");

         try (OutputStreamWriter outputStreamWriter= new OutputStreamWriter(connection.getOutputStream())) {
            outputStreamWriter.write(issueObject.toString());
         }

         try (InputStreamReader inputStreamReader = new InputStreamReader(connection.getInputStream())) {
            JsonObject responseObject = JsonParser.parseReader(inputStreamReader).getAsJsonObject();

            String issueKey = responseObject.getAsJsonPrimitive("key").getAsString();

            return issueKey;
         }
      } finally {
         connection.disconnect();
      }
   }

   public void addIssueLabels(String issueKey, String... labels) throws Exception {

      List<String> newLabels = new ArrayList<>();
      for (String label : labels) {
         newLabels.add(label);
      }

      JsonObject issueObject = getIssue(issueKey);
      JsonObject issueFields = issueObject.getAsJsonObject("fields");
      JsonArray labelsArray = issueFields.getAsJsonArray("labels");

      JsonObject updatingIssueObject = new JsonObject();
      {
         JsonObject updatingFieldsObject = new JsonObject();
         JsonArray updatingLabelsArray = new JsonArray();
         for (JsonElement labelElement : labelsArray) {
            if (labelElement != null && !labelElement.isJsonNull()) {
               String label = labelElement.getAsString();
               newLabels.remove(label);
               updatingLabelsArray.add(label);
            }
         }

         for (String newLabel : newLabels) {
            updatingLabelsArray.add(newLabel);
         }
         updatingFieldsObject.add("labels", updatingLabelsArray);
         updatingIssueObject.add("fields", updatingFieldsObject);
      }

      if (newLabels.size() > 0) {
         putIssue(issueKey, updatingIssueObject);
      }
   }

   public void setIssueTargetRelease(String issueKey, String targetRelease) throws Exception {

      JsonObject updatingIssueObject = new JsonObject();
      {
         JsonObject updatingFieldsObject = new JsonObject();
         JsonObject targetReleaseObject = new JsonObject();
         targetReleaseObject.addProperty("name", targetRelease);
         updatingFieldsObject.add("customfield_12311240", targetReleaseObject);
         updatingIssueObject.add("fields", updatingFieldsObject);
      }

      putIssue(issueKey, updatingIssueObject);
   }

   private void putIssue(String issueKey, JsonObject issueObject) throws Exception {
      HttpURLConnection connection = createConnection("/issue/" + issueKey);
      try {
         connection.setDoOutput(true);
         connection.setRequestMethod("PUT");

         try (OutputStreamWriter outputStreamWriter= new OutputStreamWriter(connection.getOutputStream())) {
            outputStreamWriter.write(issueObject.toString());
         }

         connection.getInputStream().close();
      } finally {
         connection.disconnect();
      }
   }

   public void transitionIssue(String issueKey, IssueState finalStatus) throws Exception {
      IssueState status = getIssueStatus(issueKey);

      while (status != finalStatus) {
         IssueState nextStatus;

         switch (status) {
            case NEW:
               nextStatus = IssueState.TODO;
               break;
            case TODO:
               nextStatus = IssueState.IN_PROGRESS;
               break;
            case IN_PROGRESS:
               nextStatus = IssueState.READY_FOR_REVIEW;
               break;
            case READY_FOR_REVIEW:
               nextStatus = IssueState.CLOSED;
               break;
            default:
               throw new IllegalStateException("Invalid status: " + status);
         }

         for (IssueTransaction transaction : getIssueTransactions(issueKey)) {
            if (transaction.getFinalStatus() == nextStatus) {
               transitionIssue(issueKey, transaction.getId());
               status = transaction.getFinalStatus();
               break;
            }
         }
      }
   }

   public void transitionIssue(String issueKey, int transitionId) throws Exception {
      HttpURLConnection connection = createConnection("/issue/" + issueKey + "/transitions");
      connection.setDoOutput(true);
      connection.setRequestMethod("POST");

      try (OutputStreamWriter outputStreamWriter= new OutputStreamWriter(connection.getOutputStream())) {
         outputStreamWriter.write("{\"transition\":{\"id\":\"" + transitionId + "\"}}");
      }

      connection.getInputStream().close();
   }

   private JsonObject getIssue(String issueKey) throws Exception {
      HttpURLConnection connection = createConnection("/issue/" + issueKey);
      try {
         try (InputStreamReader inputStreamReader = new InputStreamReader(connection.getInputStream())) {
            return JsonParser.parseReader(inputStreamReader).getAsJsonObject();
         }
      } finally {
         connection.disconnect();
      }
   }

   public IssueState getIssueStatus(String issueKey) throws Exception {
      JsonObject issueObject = getIssue(issueKey);

      JsonObject issueFields = issueObject.getAsJsonObject("fields");

      return IssueState.fromName(issueFields.getAsJsonObject("status").getAsJsonPrimitive("name").getAsString());
   }

   public IssueTransaction[] getIssueTransactions(String issueKey) throws Exception {
      HttpURLConnection connection = createConnection("/issue/" + issueKey + "/transitions?expand=transitions.fields");
      try {
         List<IssueTransaction> issueTransactions = new ArrayList<>();

         try (InputStreamReader inputStreamReader = new InputStreamReader(connection.getInputStream())) {
            JsonObject jsonObject = JsonParser.parseReader(inputStreamReader).getAsJsonObject();

            JsonArray transitionsArray = jsonObject.getAsJsonArray("transitions");

            for (JsonElement transitionElement : transitionsArray) {
               JsonObject transitionObject = transitionElement.getAsJsonObject();

               int transitionId = transitionObject.getAsJsonPrimitive("id").getAsInt();
               IssueState transitionFinalStatus = IssueState.fromName(transitionObject.getAsJsonObject("to").getAsJsonPrimitive("name").getAsString());

               issueTransactions.add(new IssueTransaction()
                  .setId(transitionId)
                  .setFinalStatus(transitionFinalStatus));
            }
         }

         return issueTransactions.toArray(IssueTransaction[]::new);
      } finally {
         connection.disconnect();
      }
   }

   public Issue[] loadProjectIssues(String projectKey, boolean parseCustomFields) throws Exception {
      int total = 0;
      final int MAX_RESULTS = 250;
      List<Issue> issues = Collections.synchronizedList(new ArrayList<>());

      HttpURLConnection searchConnection = createConnection("/search?jql=project=%22" + projectKey + "%22&maxResults=0");
      try {
         try (InputStreamReader inputStreamReader = new InputStreamReader(searchConnection.getInputStream())) {
            JsonObject jsonObject = JsonParser.parseReader(inputStreamReader).getAsJsonObject();

            total = jsonObject.getAsJsonPrimitive("total").getAsInt();
         }
      } finally {
         searchConnection.disconnect();
      }

      int taskCount = (int)Math.ceil((double)total / (double)MAX_RESULTS);
      List<Callable<Integer>> tasks = new ArrayList<>();

      for (int i = 0; i < taskCount; i++) {
         final int start = i * MAX_RESULTS;

         tasks.add(() -> loadProjectIssues(projectKey, parseCustomFields, start, MAX_RESULTS, issues));
      }

      long beginTimestamp = System.nanoTime();
      Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()).invokeAll(tasks);
      long endTimestamp = System.nanoTime();

      logger.info("Loaded " + issues.size() + " issues in " + (endTimestamp - beginTimestamp) / 1000000 + " milliseconds");

      return issues.toArray(Issue[]::new);
   }


   public int loadProjectIssues(String projectKey, boolean parseCustomFields, int start, int maxResults, List<Issue> issues) throws Exception {
      int result = 0;

      HttpURLConnection connection = createConnection("/search?jql=project=%22" + projectKey + "%22&fields=*all&maxResults=" + maxResults + "&startAt=" + start);
      try {
         try (InputStreamReader inputStreamReader = new InputStreamReader(connection.getInputStream())) {
            JsonObject jsonObject = JsonParser.parseReader(inputStreamReader).getAsJsonObject();

            JsonArray issuesArray = jsonObject.getAsJsonArray("issues");

            for (JsonElement issueElement : issuesArray) {
               JsonObject issueObject = issueElement.getAsJsonObject();

               issues.add(parseIssue(issueObject, parseCustomFields));

               result++;
            }
         }
      } finally {
         connection.disconnect();
      }

      return result;
   }


   private Issue parseIssue(JsonObject issueObject, boolean parseCustomFields) {
      String issueKey = issueObject.getAsJsonPrimitive("key").getAsString();
      logger.debug("loading issue " + issueKey);

      JsonObject issueFields = issueObject.getAsJsonObject("fields");

      JsonElement issueAssigneeElement = issueFields.get("assignee");
      String issueAssignee = issueAssigneeElement != null && !issueAssigneeElement.isJsonNull() ?
         issueFields.getAsJsonObject("assignee").getAsJsonPrimitive("name").getAsString() : null;
      String issueCreator = issueFields.getAsJsonObject("creator").getAsJsonPrimitive("name").getAsString();
      String issueReporter = issueFields.getAsJsonObject("reporter").getAsJsonPrimitive("name").getAsString();
      String issueStatus = issueFields.getAsJsonObject("status").getAsJsonPrimitive("name").getAsString();
      JsonElement issueDescriptionElement = issueFields.get("description");
      String issueDescription = issueDescriptionElement == null || issueDescriptionElement.isJsonNull() ?
         null : issueDescriptionElement.getAsString();
      String issueType = issueFields.getAsJsonObject("issuetype").getAsJsonPrimitive("name").getAsString();
      String issueSummary = issueFields.getAsJsonPrimitive("summary").getAsString();

      Issue issue = new Issue()
         .setKey(issueKey)
         .setAssignee(issueAssignee)
         .setCreator(issueCreator)
         .setReporter(issueReporter)
         .setState(IssueState.fromName(issueStatus))
         .setSummary(issueSummary)
         .setDescription(issueDescription)
         .setType(IssueType.fromName(issueType));

      for (JsonElement issueLabelElement : issueFields.get("labels").getAsJsonArray()) {
         if (issueLabelElement != null && !issueLabelElement.isJsonNull()) {
            issue.getLabels().add(issueLabelElement.getAsString());
         }
      }

      issue.setCustomerPriority(CustomerPriority.NONE);
      issue.setSecurityImpact(SecurityImpact.NONE);

      if (parseCustomFields) {
         //"id":"customfield_12314640","name":"Upstream Jira"
         JsonElement upstreamJiraElement = issueFields.get("customfield_12314640");

         if (!upstreamJiraElement.isJsonNull()) {
            String upstreamIssue = upstreamJiraElement.getAsString();
            Matcher upstreamIssueMatcher = upstreamIssuePattern.matcher(upstreamIssue);

            while (upstreamIssueMatcher.find()) {
               String upstreamIssueKey = upstreamIssueMatcher.group();
               logger.debug("linking issue " + upstreamIssueKey);
               issue.getIssues().add(upstreamIssueKey);
            }
         }

         JsonElement targetReleaseElement = issueFields.get(TARGET_RELEASE_FIELD);
         if (targetReleaseElement != null && !targetReleaseElement.isJsonNull()) {
            issue.setTargetRelease(targetReleaseElement.getAsJsonObject().get("name").getAsString());
         }

         //"id":"customfield_12312340","name":"GSS Priority"
         //"id":"customfield_12310120","name":"Help Desk Ticket Reference"
         //"id":"customfield_12310021","name":"Support Case Reference"
         JsonElement gssPriorityElement = issueFields.get("customfield_12312340");
         JsonElement helpDeskTicketReferenceElement = issueFields.get("customfield_12310120");
         JsonElement supportCaseReferenceElement = issueFields.get("customfield_12310021");
         JsonElement linksElement = issueFields.get("issuelinks");
         issue.setCustomer((gssPriorityElement != null && !gssPriorityElement.isJsonNull()) ||
            (helpDeskTicketReferenceElement != null && !helpDeskTicketReferenceElement.isJsonNull()) ||
            (supportCaseReferenceElement != null && !supportCaseReferenceElement.isJsonNull()) ||
            (linksElement != null && !linksElement.isJsonNull() && linksElement.toString().matches("PATCH-[0-9]+]")));
         issue.setCustomerPriority(gssPriorityElement != null && !gssPriorityElement.isJsonNull() ? CustomerPriority.fromName(
            gssPriorityElement.getAsJsonObject().get("value").getAsString()) : CustomerPriority.NONE);

         //"id":"customfield_12311640","name":"Security Sensitive Issue"
         JsonElement securitySensitiveIssueElement = issueFields.get("customfield_12311640");
         issue.setSecurity(securitySensitiveIssueElement != null && !securitySensitiveIssueElement.isJsonNull());
         if (issueDescription != null && issueDescription.startsWith("Security Tracking Issue")) {
            Matcher securityImpactMatcher = securityImpactPattern.matcher(issueDescription);
            if (securityImpactMatcher.find()) {
               issue.setSecurityImpact(SecurityImpact.fromName(securityImpactMatcher.group(1)));
            }
         }
      }

      return issue;
   }

   private HttpURLConnection createConnection(String url) throws Exception {
      URL upstreamJIRA = new URL(serverURL + url);
      HttpURLConnection connection = (HttpURLConnection)upstreamJIRA.openConnection();
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");

      if (authString != null) {
         connection.setRequestProperty("Authorization", authString);
      }

      return connection;
   }
}
