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

import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CommitProcessor {
   private final static Logger logger = LoggerFactory.getLogger(CommitProcessor.class);

   private final static Pattern upstreamIssuePattern = Pattern.compile("ARTEMIS-[0-9]+");
   private final static Pattern downstreamIssuePattern = Pattern.compile("ENTMQBR-[0-9]+");


   private static final String UPSTREAM_TEST_COVERAGE_LABEL = "upstream-test-coverage";
   private static final String NO_TESTING_NEEDED_LABEL = "no-testing-needed";

   private static final String COMMITTER_NAME = "rh-messaging-ci";
   private static final String COMMITTER_EMAIL = "messaging-infra@redhat.com";

   private static final String FUTURE_GA_RELEASE = "Future GA";

   private Git git;
   private ReleaseVersion candidateReleaseVersion;
   private boolean requireReleaseIssues;
   private IssueClient upstreamIssueClient;
   private IssueClient downstreamIssueClient;
   private AssigneeResolver assigneeResolver;
   private Map<String, Issue> upstreamIssues;
   private Map<String, Issue> downstreamIssues;
   private HashMap<String, Map.Entry<ReleaseVersion, RevCommit>> cherryPickedCommits;
   private Map<String, Commit> confirmedCommits;
   private Map<String, Issue> confirmedUpstreamIssues;
   private Map<String, Issue> confirmedDownstreamIssues;
   private CustomerPriority downstreamIssuesCustomerPriority;
   private SecurityImpact downstreamIssuesSecurityImpact;
   private boolean checkIncompleteCommits;


   public CommitProcessor(Git git, ReleaseVersion candidateReleaseVersion, boolean requireReleaseIssues,
                          IssueClient upstreamIssueClient, IssueClient downstreamIssueClient,
                          AssigneeResolver assigneeResolver, Map<String, Issue> upstreamIssues,
                          Map<String, Issue> downstreamIssues, HashMap<String, Map.Entry<ReleaseVersion, RevCommit>> cherryPickedCommits,
                          Map<String, Commit> confirmedCommits, Map<String, Issue> confirmedUpstreamIssues,
                          Map<String, Issue> confirmedDownstreamIssues,
                          CustomerPriority downstreamIssuesCustomerPriority,
                          SecurityImpact downstreamIssuesSecurityImpact,
                          boolean checkIncompleteCommits) {
      this.git = git;
      this.candidateReleaseVersion = candidateReleaseVersion;
      this.requireReleaseIssues = requireReleaseIssues;
      this.upstreamIssueClient = upstreamIssueClient;
      this.downstreamIssueClient = downstreamIssueClient;
      this.assigneeResolver = assigneeResolver;
      this.upstreamIssues = upstreamIssues;
      this.downstreamIssues = downstreamIssues;
      this.cherryPickedCommits = cherryPickedCommits;
      this.confirmedCommits = confirmedCommits;
      this.confirmedUpstreamIssues = confirmedUpstreamIssues;
      this.confirmedDownstreamIssues = confirmedDownstreamIssues;
      this.downstreamIssuesCustomerPriority = downstreamIssuesCustomerPriority;
      this.downstreamIssuesSecurityImpact = downstreamIssuesSecurityImpact;
      this.checkIncompleteCommits = checkIncompleteCommits;
   }

   public Commit process(RevCommit upstreamCommit) throws Exception {
      logger.info("Processing " + upstreamCommit.getName() + " - " + upstreamCommit.getShortMessage());

      ReleaseVersion candidateReleaseVersion = this.candidateReleaseVersion;
      Map.Entry<ReleaseVersion, RevCommit> cherryPickedCommit = cherryPickedCommits.get(upstreamCommit.getName());
      if (cherryPickedCommit != null) {
         candidateReleaseVersion = cherryPickedCommit.getKey();
      }

      String release = "AMQ " + candidateReleaseVersion.getMajor() + "." +
         candidateReleaseVersion.getMinor() + "." +
         candidateReleaseVersion.getPatch() + ".GA";
      String qualifier = candidateReleaseVersion.getQualifier();


      Commit confirmedCommit = confirmedCommits.get(upstreamCommit.getName());
      List<CommitTask> confirmedTasks = null;
      if (confirmedCommit != null) {
         confirmedTasks = confirmedCommit.getTasks();
      }

      Commit commit = new Commit().setUpstreamCommit(upstreamCommit.getName())
         .setSummary(upstreamCommit.getShortMessage()).setState(CommitState.DONE);

      Matcher upstreamIssueMatcher = upstreamIssuePattern.matcher(upstreamCommit.getShortMessage());

      String upstreamIssueKey = null;
      if (upstreamIssueMatcher.find()) {
         upstreamIssueKey = upstreamIssueMatcher.group();
      } else if (cherryPickedCommit == null) {
         logger.info("SKIPPED because the commit message does not include an upstream issue key");
         commit.setState(CommitState.SKIPPED).setReason("NO_UPSTREAM_ISSUE");
         return commit;
      }

      Issue upstreamIssue = null;
      if (upstreamIssueKey != null) {
         if (upstreamIssueMatcher.find() && cherryPickedCommit == null) {
            logger.warn("SKIPPED because the commit message includes multiple upstream issue keys");
            commit.setState(CommitState.SKIPPED).setReason("MULTIPLE_UPSTREAM_ISSUES");
            return commit;
         }

         upstreamIssue = upstreamIssueKey != null ? upstreamIssues.get(upstreamIssueKey) : null;

         commit.setUpstreamIssue(upstreamIssue.getKey());

         if (upstreamIssue == null && cherryPickedCommit == null) {
            logger.warn("SKIPPED because the upstream issue is not found: " + upstreamIssueKey);
            commit.setState(CommitState.SKIPPED).setReason("UPSTREAM_ISSUE_NOT_FOUND");
            return commit;
         }
      }


      commit.setAuthor(upstreamCommit.getAuthorIdent().getName());
      commit.setReleaseVersion(candidateReleaseVersion);
      commit.setDownstreamCommit(cherryPickedCommit != null ? cherryPickedCommit.getValue().getName() : null);
      commit.setUpstreamTestCoverage(hasTestCoverage(upstreamCommit));


      // Select downstream issues
      String selectedTargetRelease = null;
      List<Issue> selectedDownstreamIssues = null;
      List<Issue> allDownstreamIssues = new ArrayList<>();
      Map<String, List<Issue>> downstreamIssuesGroups = groupDownstreamIssuesByTargetRelease(upstreamIssue, release);
      if (downstreamIssuesGroups != null && downstreamIssuesGroups.size() > 0) {
         selectedTargetRelease = selectRelease(downstreamIssuesGroups.keySet(), release);
         selectedDownstreamIssues = downstreamIssuesGroups.get(selectedTargetRelease);

         for (List<Issue> downstreamIssuesGroup : downstreamIssuesGroups.values()) {
            for (Issue downstreamIssue : downstreamIssuesGroup) {
               allDownstreamIssues.add(downstreamIssue);
               commit.getDownstreamIssues().add(downstreamIssue.getKey());
            }
         }
      }

      User assignee = assigneeResolver.getAssignee(upstreamCommit, upstreamIssue, selectedDownstreamIssues);

      if (selectedDownstreamIssues != null && selectedDownstreamIssues.size() > 0) {
         // Commit related to downstream issues

         if (cherryPickedCommit != null) {
            // Commit cherry-picked, check the downstream issues

            if(release.equals(selectedTargetRelease)) {
               processDownstreamIssues(commit, release, qualifier, selectedDownstreamIssues, confirmedTasks, assignee);
            } else {
               if (requireReleaseIssues) {
                  if (checkIncompleteCommits) {
                     logger.warn("INCOMPLETE because no downstream issues with the required target release");
                     commit.setState(CommitState.INCOMPLETE).setReason("NO_DOWNSTREAM_ISSUES_WITH_REQUIRED_TARGET_RELEASE");
                  }
               }
            }
         } else {
            // Commit not cherry-picked

            if (release.equals(selectedTargetRelease)) {
               // The selected downstream issues have the required target release

               if (processCommitTask(commit, release, qualifier, CommitTaskType.CHERRY_PICK_UPSTREAM_COMMIT, upstreamCommit.getName(),
                  selectedDownstreamIssues.stream().map(Issue::getKey).collect(Collectors.joining(",")), confirmedTasks, assignee)) {

                  commit.setState(CommitState.DONE);

                  processDownstreamIssues(commit, release, qualifier, selectedDownstreamIssues, confirmedTasks, assignee);
               } else {
                  commit.setState(CommitState.CONFLICTED);
               }
            } else {
               // The selected downstream issues do not have the required target release

               if (requireCherryPick(allDownstreamIssues)) {
                  // At least one downstream issue match sufficient criteria to cherry-pick the commit

                  if (requireReleaseIssues) {
                     // The commits related to downstream issues already fixed in a previous release require
                     // a downstream release issue if cherry-picked to a branch with previous releases
                     commit.setState(CommitState.BLOCKED);

                     for (Issue downstreamIssue : selectedDownstreamIssues) {
                        processCommitTask(commit, release, qualifier, CommitTaskType.CLONE_DOWNSTREAM_ISSUE, downstreamIssue.getKey(), null, confirmedTasks, assignee);
                     }
                  } else {
                     // The commits related to downstream issues already fixed in a previous release do not require
                     // a downstream release issue if cherry-picked to a branch without previous releases

                     if (processCommitTask(commit, release, qualifier, CommitTaskType.CHERRY_PICK_UPSTREAM_COMMIT, upstreamCommit.getName(),
                        selectedDownstreamIssues.stream().map(Issue::getKey).collect(Collectors.joining(",")), confirmedTasks, assignee)) {

                        commit.setState(CommitState.DONE);
                     }
                  }
               } else {
                  commit.setState(CommitState.SKIPPED).setReason("DOWNSTREAM_ISSUE_NOT_SUFFICIENT");
               }
            }
         }
      } else {
         // No selected downstream issues

         if (cherryPickedCommit != null) {
            // Commit cherry-picked but no downstream issues
            if (checkIncompleteCommits) {
               logger.warn("INCOMPLETE because no downstream issues");
               commit.setState(CommitState.INCOMPLETE).setReason("NO_DOWNSTREAM_ISSUES");
            }
         } else {
            // Commit cherry-picked but no downstream issues

            if ((confirmedUpstreamIssues != null && confirmedUpstreamIssues.containsKey(upstreamIssue.getKey())) ||
               (confirmedUpstreamIssues == null && upstreamIssue.getType() == IssueType.BUG)) {
               commit.setState(CommitState.BLOCKED);
               processCommitTask(commit, release, qualifier, CommitTaskType.CLONE_UPSTREAM_ISSUE, upstreamIssue.getKey(), null, confirmedTasks, assignee);
            } else {
               commit.setState(CommitState.SKIPPED).setReason("UPSTREAM_ISSUE_NOT_SUFFICIENT");
            }
         }
      }

      return commit;
   }

   private boolean requireCherryPick(List<Issue> downstreamIssues) {
      for (Issue downstreamIssue : downstreamIssues) {
         if ((confirmedDownstreamIssues != null && confirmedDownstreamIssues.containsKey(downstreamIssue.getKey())) ||
            (confirmedDownstreamIssues == null && downstreamIssue.getType() == IssueType.BUG &&
               ((downstreamIssue.isCustomer() && downstreamIssuesCustomerPriority.compareTo(downstreamIssue.getCustomerPriority()) <= 0) ||
                  (downstreamIssue.isSecurity() && downstreamIssuesSecurityImpact.compareTo(downstreamIssue.getSecurityImpact()) <= 0)))) {
            return true;
         }
      }

      return false;
   }

   private void processDownstreamIssues(Commit commit, String release, String qualifier, List<Issue> downstreamIssues, List<CommitTask> confirmedTasks, User assignee) throws Exception {
      for (Issue downstreamIssue : downstreamIssues) {
         //Check if the downstream issue define a target release
         if (downstreamIssue.getTargetRelease() == null || downstreamIssue.getTargetRelease().isEmpty() || downstreamIssue.getTargetRelease().equals(FUTURE_GA_RELEASE)) {
            if (checkIncompleteCommits) {
               commit.setState(CommitState.INCOMPLETE);
               processCommitTask(commit, release, qualifier, CommitTaskType.SET_DOWNSTREAM_ISSUE_TARGET_RELEASE, downstreamIssue.getKey(), release, confirmedTasks, assignee);
            }
         }

         //Check if the downstream issue has the qualifier label
         if (!downstreamIssue.getLabels().contains(qualifier)) {
            if (checkIncompleteCommits) {
               commit.setState(CommitState.INCOMPLETE);
               processCommitTask(commit, release, qualifier, CommitTaskType.ADD_DOWNSTREAM_ISSUE_LABEL, downstreamIssue.getKey(), qualifier, confirmedTasks, assignee);
            }
         }

         //Check if the downstream issue has the upstream-test-coverage label
         if (commit.hasUpstreamTestCoverage() && !downstreamIssue.getLabels().contains(UPSTREAM_TEST_COVERAGE_LABEL) &&
            !downstreamIssue.getLabels().contains(NO_TESTING_NEEDED_LABEL)){
            if (checkIncompleteCommits) {
               commit.setState(CommitState.INCOMPLETE);
               processCommitTask(commit, release, qualifier, CommitTaskType.ADD_DOWNSTREAM_ISSUE_LABEL, downstreamIssue.getKey(), UPSTREAM_TEST_COVERAGE_LABEL, confirmedTasks, assignee);
            }
         }

         //Check if the downstream issue is ready for review
         if (downstreamIssue.getState() != IssueState.READY_FOR_REVIEW && downstreamIssue.getState() != IssueState.CLOSED) {
            if (checkIncompleteCommits) {
               commit.setState(CommitState.INCOMPLETE);
               processCommitTask(commit, release, qualifier, CommitTaskType.TRANSITION_DOWNSTREAM_ISSUE, downstreamIssue.getKey(), IssueState.READY_FOR_REVIEW.name(), confirmedTasks, assignee);
            }
         }
      }
   }

   private boolean processCommitTask(Commit commit, String release, String qualifier, CommitTaskType type, String key, String value, List<CommitTask> confirmedTasks, User assignee) throws Exception {
      boolean executed = false;
      CommitTask confirmedTask = getCommitTask(type, key, value, confirmedTasks);

      if (type == CommitTaskType.CHERRY_PICK_UPSTREAM_COMMIT) {
         RevCommit upstreamCommit = git.getRepository().parseCommit(git.getRepository().resolve(key));
         CherryPickResult cherryPickResult = git.cherryPick().include(upstreamCommit).setNoCommit(true).call();

         if (cherryPickResult.getStatus() == CherryPickResult.CherryPickStatus.OK) {
            String commitMessage = upstreamCommit.getFullMessage() + "\n" +
               "(cherry picked from commit " + upstreamCommit.getName() + ")\n\n" +
               "downstream: " + value;

            RevCommit cherryPickedCommit = git.commit().setMessage(commitMessage)
               .setAuthor(upstreamCommit.getAuthorIdent())
               .setCommitter(COMMITTER_NAME, COMMITTER_EMAIL)
               .call();

            cherryPickedCommits.put(upstreamCommit.getName(), new AbstractMap.SimpleEntry(candidateReleaseVersion, cherryPickedCommit));

            executed = true;
         } else {
            logger.warn("Error cherry picking: " + cherryPickResult.getStatus());

            git.reset().setMode(ResetCommand.ResetType.HARD).call();

            executed = false;
         }
      } else if (confirmedTask != null) {
         if (type == CommitTaskType.ADD_DOWNSTREAM_ISSUE_LABEL) {
            downstreamIssueClient.addIssueLabels(key, value);
            downstreamIssues.get(key).getLabels().add(value);
         } else if (type == CommitTaskType.SET_DOWNSTREAM_ISSUE_TARGET_RELEASE) {
            downstreamIssueClient.setIssueTargetRelease(key, value);
            downstreamIssues.get(key).setTargetRelease(value);
         } else if (type == CommitTaskType.TRANSITION_DOWNSTREAM_ISSUE) {
            IssueState downstreamIssueState = IssueState.valueOf(value);
            downstreamIssueClient.transitionIssue(key, downstreamIssueState);
            downstreamIssues.get(key).setState(downstreamIssueState);
         } else if (type == CommitTaskType.CLONE_DOWNSTREAM_ISSUE) {
            Issue downstreamIssue = downstreamIssueClient.cloneIssue("ENTMQBR", key, release);

            for (String upstreamIssueKey : downstreamIssue.getIssues()) {
               Issue upstreamIssue = upstreamIssues.get(upstreamIssueKey);
               upstreamIssue.getIssues().add(downstreamIssue.getKey());
            }

            downstreamIssues.put(downstreamIssue.getKey(), downstreamIssue);
         } else if (type == CommitTaskType.CLONE_UPSTREAM_ISSUE) {
            Issue upstreamIssue = upstreamIssues.get(key);
            List<String> labels = new ArrayList<>();
            labels.add(qualifier);
            if (commit.hasUpstreamTestCoverage()) {
               labels.add(UPSTREAM_TEST_COVERAGE_LABEL);
            }

            Issue downstreamIssue = downstreamIssueClient.createIssue("ENTMQBR", upstreamIssue.getSummary(),
               upstreamIssue.getDescription(), upstreamIssue.getType(), assignee.getDownstreamUsername(),
               "https://issues.apache.org/jira/browse/" + upstreamIssue.getKey(), release, labels);

            downstreamIssues.put(downstreamIssue.getKey(), downstreamIssue);
            upstreamIssue.getIssues().add(downstreamIssue.getKey());
         } else {
            throw new IllegalStateException("Commit task type not supported: " + type);
         }

         executed = true;
      }

      commit.getTasks().add(new CommitTask().setType(type).setKey(key).setValue(value).setAssignee(assignee).setExecuted(executed));

      return executed;
   }

   private CommitTask getCommitTask(CommitTaskType type, String key, String value, List<CommitTask> tasks) {
      if (tasks != null) {
         for (CommitTask task : tasks) {
            if (Objects.equals(type, task.getType()) &&
               Objects.equals(key, task.getKey()) &&
               Objects.equals(value, task.getValue())) {
               return task;
            }
         }
      }

      return null;
   }

   private boolean hasTestCoverage(RevCommit upstreamCommit) throws Exception {
      try (ObjectReader reader = git.getRepository().newObjectReader()) {
         CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
         CanonicalTreeParser newTreeIter = new CanonicalTreeParser();

         oldTreeIter.reset(reader, upstreamCommit.getParent(0).getTree());
         newTreeIter.reset(reader, upstreamCommit.getTree());

         List<DiffEntry> diffList = git.diff().setOldTree(oldTreeIter).setNewTree(newTreeIter).call();

         for (DiffEntry entry : diffList) {
            String path = entry.getNewPath();
            if (path.contains("test")) {
               return true;
            }
         }
      }

      return false;
   }

   private String selectRelease(Set<String> releases, String release) {
      String selectedRelease = null;
      for (String selectingRelease : releases) {
         if (release.equals(selectingRelease)) {
            return selectingRelease;
         } else if (selectedRelease == null || ReleaseVersion.compare(selectingRelease, selectedRelease) > 0) {
            selectedRelease = selectingRelease;
         }
      }

      return selectedRelease;
   }

   private Map<String, List<Issue>> groupDownstreamIssuesByTargetRelease(Issue upstreamIssue, String release) {
      // Check if the upstream issue is related to at least one downstream issue
      if (upstreamIssue == null || upstreamIssue.getIssues() == null || upstreamIssue.getIssues().isEmpty()) {
         return null;
      }

      Map<String, List<Issue>> downstreamIssuesGroups = new HashMap<>();
      for (String downstreamIssueKey : upstreamIssue.getIssues()) {
         Issue downstreamIssue = downstreamIssues.get(downstreamIssueKey);
         if (downstreamIssue != null) {
            String downstreamIssueTargetRelease = downstreamIssue.getTargetRelease();
            if (downstreamIssueTargetRelease == null ||
               downstreamIssueTargetRelease.isEmpty() ||
               downstreamIssueTargetRelease.equals(FUTURE_GA_RELEASE)) {
               if (requireReleaseIssues) {
                  downstreamIssueTargetRelease = null;
               } else {
                  downstreamIssueTargetRelease = release;
                  logger.warn("Downstream issue without target release: " + downstreamIssueKey);
               }
            }

            if (downstreamIssueTargetRelease != null) {
               List<Issue> downstreamIssuesGroup = downstreamIssuesGroups.get(downstreamIssueTargetRelease);
               if (downstreamIssuesGroup == null) {
                  downstreamIssuesGroup = new ArrayList<>();
                  downstreamIssuesGroups.put(downstreamIssueTargetRelease, downstreamIssuesGroup);
               }
               downstreamIssuesGroup.add(downstreamIssue);
            }
         } else {
            logger.warn("Downstream issue not found: " + downstreamIssueKey);
         }
      }

      return downstreamIssuesGroups;
   }
}
