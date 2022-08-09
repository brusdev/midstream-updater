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

import com.redhat.midstream.updater.git.GitCommit;
import com.redhat.midstream.updater.git.GitRepository;
import com.redhat.midstream.updater.issues.Issue;
import com.redhat.midstream.updater.issues.IssueManager;
import com.redhat.midstream.updater.issues.IssueState;
import com.redhat.midstream.updater.issues.IssueType;
import org.apache.maven.plugin.surefire.log.api.NullConsoleLogger;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.stream.Stream;

public class CommitProcessor {
   private final static Logger logger = LoggerFactory.getLogger(CommitProcessor.class);

   private final static Pattern upstreamIssuePattern = Pattern.compile("ARTEMIS-[0-9]+");
   private final static Pattern downstreamIssuePattern = Pattern.compile("ENTMQBR-[0-9]+");


   private static final String UPSTREAM_TEST_COVERAGE_LABEL = "upstream-test-coverage";
   private static final String NO_TESTING_NEEDED_LABEL = "no-testing-needed";

   private static final String COMMITTER_NAME = "rh-messaging-ci";
   private static final String COMMITTER_EMAIL = "messaging-infra@redhat.com";

   private static final String FUTURE_GA_RELEASE = "Future GA";

   private static final String TEST_PATH = "src/test/java/";

   private GitRepository gitRepository;
   private ReleaseVersion candidateReleaseVersion;
   private boolean requireReleaseIssues;
   private IssueManager upstreamIssueManager;
   private IssueManager downstreamIssueManager;
   private AssigneeResolver assigneeResolver;
   private HashMap<String, Map.Entry<ReleaseVersion, GitCommit>> cherryPickedCommits;
   private Map<String, Commit> confirmedCommits;
   private Map<String, Issue> confirmedUpstreamIssues;
   private Map<String, Issue> confirmedDownstreamIssues;
   private CustomerPriority downstreamIssuesCustomerPriority;
   private SecurityImpact downstreamIssuesSecurityImpact;
   private boolean checkIncompleteCommits;
   private boolean scratch;
   private boolean skipCommitTest;



   public CommitProcessor(GitRepository gitRepository, ReleaseVersion candidateReleaseVersion, boolean requireReleaseIssues,
                          IssueManager upstreamIssueManager, IssueManager downstreamIssueManager, AssigneeResolver assigneeResolver,
                          HashMap<String, Map.Entry<ReleaseVersion, GitCommit>> cherryPickedCommits,
                          Map<String, Commit> confirmedCommits, Map<String, Issue> confirmedUpstreamIssues,
                          Map<String, Issue> confirmedDownstreamIssues,
                          CustomerPriority downstreamIssuesCustomerPriority,
                          SecurityImpact downstreamIssuesSecurityImpact,
                          boolean checkIncompleteCommits, boolean scratch, boolean skipCommitTest) {
      this.gitRepository = gitRepository;
      this.candidateReleaseVersion = candidateReleaseVersion;
      this.requireReleaseIssues = requireReleaseIssues;
      this.upstreamIssueManager = upstreamIssueManager;
      this.downstreamIssueManager = downstreamIssueManager;
      this.assigneeResolver = assigneeResolver;
      this.cherryPickedCommits = cherryPickedCommits;
      this.confirmedCommits = confirmedCommits;
      this.confirmedUpstreamIssues = confirmedUpstreamIssues;
      this.confirmedDownstreamIssues = confirmedDownstreamIssues;
      this.downstreamIssuesCustomerPriority = downstreamIssuesCustomerPriority;
      this.downstreamIssuesSecurityImpact = downstreamIssuesSecurityImpact;
      this.checkIncompleteCommits = checkIncompleteCommits;
      this.scratch = scratch;
      this.skipCommitTest = skipCommitTest;
   }

   public Commit process(GitCommit upstreamCommit) throws Exception {
      logger.info("Processing " + upstreamCommit.getName() + " - " + upstreamCommit.getShortMessage());

      ReleaseVersion candidateReleaseVersion = this.candidateReleaseVersion;
      Map.Entry<ReleaseVersion, GitCommit> cherryPickedCommit = cherryPickedCommits.get(upstreamCommit.getName());
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
         commit.setUpstreamIssue(upstreamIssueKey);

         if (upstreamIssueMatcher.find() && cherryPickedCommit == null) {
            logger.warn("SKIPPED because the commit message includes multiple upstream issue keys");
            commit.setState(CommitState.FAILED).setReason("MULTIPLE_UPSTREAM_ISSUES");
            return commit;
         }

         upstreamIssue = upstreamIssueKey != null ? upstreamIssueManager.getIssue(upstreamIssueKey) : null;

         if (upstreamIssue == null && cherryPickedCommit == null) {
            logger.warn("SKIPPED because the upstream issue is not found: " + upstreamIssueKey);
            commit.setState(CommitState.FAILED).setReason("UPSTREAM_ISSUE_NOT_FOUND");
            return commit;
         }
      }


      commit.setAuthor(upstreamCommit.getAuthorName());
      commit.setReleaseVersion(candidateReleaseVersion.toString());
      commit.setDownstreamCommit(cherryPickedCommit != null ? cherryPickedCommit.getValue().getName() : null);
      commit.setTests(getCommitTests(upstreamCommit));


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

      commit.setAssignee(assigneeResolver.getAssignee(upstreamCommit, upstreamIssue, selectedDownstreamIssues).getUsername());

      if (selectedDownstreamIssues != null && selectedDownstreamIssues.size() > 0) {
         // Commit related to downstream issues

         if (cherryPickedCommit != null) {
            // Commit cherry-picked, check the downstream issues

            if (this.candidateReleaseVersion.compareWithoutQualifierTo(candidateReleaseVersion) == 0) {
               if(release.equals(selectedTargetRelease)) {
                  if (processDownstreamIssues(commit, release, qualifier, selectedDownstreamIssues, confirmedTasks)) {
                     commit.setState(CommitState.DONE);
                  } else {
                     commit.setState(CommitState.INCOMPLETE);
                  }
               } else {
                  if (requireReleaseIssues) {
                     logger.warn("INCOMPLETE because no downstream issues with the required target release");

                     if (cloneDownstreamIssues(commit, release, qualifier, selectedDownstreamIssues, confirmedTasks)) {
                        commit.setState(CommitState.DONE);
                     } else {
                        commit.setState(CommitState.INCOMPLETE).setReason("NO_DOWNSTREAM_ISSUES_WITH_REQUIRED_TARGET_RELEASE");
                     }
                  }
               }
            }
         } else {
            // Commit not cherry-picked

            if (release.equals(selectedTargetRelease)) {
               // The selected downstream issues have the required target release

               if (processCommitTask(commit, release, qualifier, CommitTaskType.CHERRY_PICK_UPSTREAM_COMMIT, upstreamCommit.getName(),
                  selectedDownstreamIssues.stream().map(Issue::getKey).collect(Collectors.joining(",")), confirmedTasks)) {

                  if (processDownstreamIssues(commit, release, qualifier, selectedDownstreamIssues, confirmedTasks)) {
                     commit.setState(CommitState.DONE);
                  } else {
                     commit.setState(CommitState.INCOMPLETE);
                  }
               } else {
                  commit.setState(CommitState.TODO);
               }
            } else {
               // The selected downstream issues do not have the required target release

               if (requireCherryPick(allDownstreamIssues)) {
                  // At least one downstream issue match sufficient criteria to cherry-pick the commit

                  if (requireReleaseIssues) {
                     // The commits related to downstream issues already fixed in a previous release require
                     // a downstream release issue if cherry-picked to a branch with previous releases
                     if (cloneDownstreamIssues(commit, release, qualifier, selectedDownstreamIssues, confirmedTasks)) {
                        commit.setState(CommitState.DONE);
                     } else {
                        commit.setState(CommitState.BLOCKED).setReason("NO_DOWNSTREAM_ISSUES_WITH_REQUIRED_TARGET_RELEASE");
                     }
                  } else {
                     // The commits related to downstream issues already fixed in a previous release do not require
                     // a downstream release issue if cherry-picked to a branch without previous releases

                     if (processCommitTask(commit, release, qualifier, CommitTaskType.CHERRY_PICK_UPSTREAM_COMMIT, upstreamCommit.getName(),
                        selectedDownstreamIssues.stream().map(Issue::getKey).collect(Collectors.joining(",")), confirmedTasks)) {

                        commit.setState(CommitState.DONE);
                     } else {
                        commit.setState(CommitState.TODO);
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
            if (this.candidateReleaseVersion.compareWithoutQualifierTo(candidateReleaseVersion) == 0) {
               if (!commit.getSummary().startsWith("NO-JIRA")) {
                  if (checkIncompleteCommits) {
                     logger.warn("INCOMPLETE because no downstream issues");
                     commit.setState(CommitState.INCOMPLETE).setReason("NO_DOWNSTREAM_ISSUES");
                  }
               }
            }
         } else {
            // Commit not cherry-picked and no downstream issues

            if ((confirmedUpstreamIssues != null && confirmedUpstreamIssues.containsKey(upstreamIssue.getKey())) ||
               (confirmedUpstreamIssues == null && upstreamIssue.getType() == IssueType.BUG)) {
               commit.setState(CommitState.BLOCKED);
               processCommitTask(commit, release, qualifier, CommitTaskType.CLONE_UPSTREAM_ISSUE, upstreamIssue.getKey(), null, confirmedTasks);
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
            (confirmedDownstreamIssues == null && ((downstreamIssue.getType() == IssueType.BUG &&
               ((downstreamIssue.isCustomer() && downstreamIssuesCustomerPriority.compareTo(downstreamIssue.getCustomerPriority()) <= 0) ||
                  (downstreamIssue.isSecurity() && downstreamIssuesSecurityImpact.compareTo(downstreamIssue.getSecurityImpact()) <= 0) ||
                  (downstreamIssue.isPatch())))))) {
            return true;
         }
      }

      return false;
   }

   private boolean cloneDownstreamIssues(Commit commit, String release, String qualifier, List<Issue> downstreamIssues, List<CommitTask> confirmedTasks) throws Exception {
      boolean executed = true;

      for (Issue downstreamIssue : downstreamIssues) {
         executed &= processCommitTask(commit, release, qualifier, CommitTaskType.CLONE_DOWNSTREAM_ISSUE, downstreamIssue.getKey(), null, confirmedTasks);
      }

      return executed;
   }

   private boolean processDownstreamIssues(Commit commit, String release, String qualifier, List<Issue> downstreamIssues, List<CommitTask> confirmedTasks) throws Exception {
      boolean executed = true;

      for (Issue downstreamIssue : downstreamIssues) {
         //Check if the downstream issue define a target release
         if (downstreamIssue.getTargetRelease() == null || downstreamIssue.getTargetRelease().isEmpty() || downstreamIssue.getTargetRelease().equals(FUTURE_GA_RELEASE)) {
            if (checkIncompleteCommits) {
               executed &= processCommitTask(commit, release, qualifier, CommitTaskType.SET_DOWNSTREAM_ISSUE_TARGET_RELEASE, downstreamIssue.getKey(), release, confirmedTasks);
            }
         }

         //Check if the downstream issue has the qualifier label
         if (!downstreamIssue.getLabels().contains(qualifier)) {
            if (checkIncompleteCommits) {
               executed &= processCommitTask(commit, release, qualifier, CommitTaskType.ADD_DOWNSTREAM_ISSUE_LABEL, downstreamIssue.getKey(), qualifier, confirmedTasks);
            }
         }

         //Check if the downstream issue has the upstream-test-coverage label
         if (commit.getTests().size() > 0 && !downstreamIssue.getLabels().contains(UPSTREAM_TEST_COVERAGE_LABEL) &&
            !downstreamIssue.getLabels().contains(NO_TESTING_NEEDED_LABEL)){
            if (checkIncompleteCommits) {
               executed &= processCommitTask(commit, release, qualifier, CommitTaskType.ADD_DOWNSTREAM_ISSUE_LABEL, downstreamIssue.getKey(), UPSTREAM_TEST_COVERAGE_LABEL, confirmedTasks);
            }
         }

         //Check if the downstream issue is ready for review
         if (downstreamIssue.getState() != IssueState.READY_FOR_REVIEW && downstreamIssue.getState() != IssueState.CLOSED) {
            if (checkIncompleteCommits) {
               executed &= processCommitTask(commit, release, qualifier, CommitTaskType.TRANSITION_DOWNSTREAM_ISSUE, downstreamIssue.getKey(), IssueState.READY_FOR_REVIEW.name(), confirmedTasks);
            }
         }
      }

      return executed;
   }

   private boolean testCommit(Commit commit) throws Exception {
      if (commit.getTests().size() > 0) {
         //Execute tests
         ProcessBuilder mavenProcessBuilder =
            new ProcessBuilder("mvn",
                               "--show-version",
                               "--settings=/home/dbruscin/.m2/settings.xml",
                               "--activate-profiles=dev,tests,redhat-ga,redhat-brew,redhat-pnc",
                               "--define=failIfNoTests=false",
                               "--define=test=" + String.join(",", commit.getTests()),
                               "clean", "package")
               .directory(gitRepository.getDirectory().getParentFile())
               .inheritIO();

         Process mavenProcess = mavenProcessBuilder.start();

         int mavenResult = mavenProcess.waitFor();

         if (mavenResult != 0) {
            return false;
         }


         //Check tests
         List<File> surefireReportsDirectories = new ArrayList<>();
         try (Stream<Path> walk = Files.walk(Paths.get(gitRepository.getDirectory().getParent()))) {
            walk.filter(path -> Files.isDirectory(path) && path.endsWith("surefire-reports"))
               .forEach(path -> surefireReportsDirectories.add(path.toFile()));
         }

         SurefireReportParser surefireReportParser = new SurefireReportParser(surefireReportsDirectories, java.util.Locale.ENGLISH, new NullConsoleLogger());
         List<ReportTestSuite> reportTestSuites = surefireReportParser.parseXMLReportFiles();
         for (ReportTestSuite reportTestSuite : reportTestSuites) {
            if (reportTestSuite.getNumberOfFailures() > 0) {
               return false;
            }
         }
      }

      return true;
   }

   private boolean processCommitTask(Commit commit, String release, String qualifier, CommitTaskType type, String key, String value, List<CommitTask> confirmedTasks) throws Exception {
      CommitTask commitTask = new CommitTask().setType(type).setKey(key).setValue(value).setState(CommitTaskState.UNCONFIRMED);
      CommitTask confirmedTask = getCommitTask(type, key, value, confirmedTasks);

      if (type == CommitTaskType.CHERRY_PICK_UPSTREAM_COMMIT) {
         GitCommit upstreamCommit = gitRepository.resolveCommit(key);
         if (gitRepository.cherryPick(upstreamCommit)) {
            if (!skipCommitTest && !testCommit(commit)) {
               logger.warn("Error testing: " + commit.getUpstreamCommit());

               gitRepository.resetHard();

               commitTask.setState(CommitTaskState.FAILED);
            } else {
               if (scratch) {
                  commitTask.setState(CommitTaskState.SCRATCHED);
               } else {
                  String commitMessage = upstreamCommit.getFullMessage() + "\n" +
                     "(cherry picked from commit " + upstreamCommit.getName() + ")\n\n" +
                     "downstream: " + value;

                  GitCommit cherryPickedCommit = gitRepository.commit(commitMessage,
                                       upstreamCommit.getAuthorName(),
                                       upstreamCommit.getAuthorEmail(),
                                       upstreamCommit.getAuthorWhen(),
                                       upstreamCommit.getAuthorTimeZone(),
                                       COMMITTER_NAME,
                                       COMMITTER_EMAIL);

                  gitRepository.push("origin", null);

                  cherryPickedCommits.put(upstreamCommit.getName(), new AbstractMap.SimpleEntry(candidateReleaseVersion, cherryPickedCommit));

                  commitTask.setState(CommitTaskState.EXECUTED);
                  commitTask.setResult(cherryPickedCommit.getName());
               }
            }
         } else {
            logger.warn("Error cherry picking");

            gitRepository.resetHard();

            commitTask.setState(CommitTaskState.FAILED);
            commitTask.setResult("CHERRY_PICK_FAILED");
         }
      } else if (confirmedTask != null) {
         if (scratch) {
            commitTask.setState(CommitTaskState.SCRATCHED);
         } else {
            if (type == CommitTaskType.ADD_DOWNSTREAM_ISSUE_LABEL) {
               downstreamIssueManager.addIssueLabels(key, value);
               downstreamIssueManager.getIssue(key).getLabels().add(value);
            } else if (type == CommitTaskType.SET_DOWNSTREAM_ISSUE_TARGET_RELEASE) {
               downstreamIssueManager.setIssueTargetRelease(key, value);
               downstreamIssueManager.getIssue(key).setTargetRelease(value);
            } else if (type == CommitTaskType.TRANSITION_DOWNSTREAM_ISSUE) {
               IssueState downstreamIssueState = IssueState.valueOf(value);
               downstreamIssueManager.transitionIssue(key, downstreamIssueState);
               downstreamIssueManager.getIssue(key).setState(downstreamIssueState);
            } else if (type == CommitTaskType.CLONE_DOWNSTREAM_ISSUE) {
               Issue cloningIssue = downstreamIssueManager.getIssue(key);
               ReleaseVersion releaseVersion = new ReleaseVersion(release);
               String summaryPrefix = "[" + releaseVersion.getMajor() + "." + releaseVersion.getMinor() + "]";

               if (cloningIssue.getIssues().size() != 1) {
                  throw new IllegalStateException("Invalid number of upstream issues to clone");
               }

               List<String> labels = new ArrayList<>();
               for (String label : cloningIssue.getLabels()) {
                  if (!label.startsWith("CR")) {
                     labels.add(label);
                  }
               }

               Issue clonedIssue = downstreamIssueManager.createIssue(
                  summaryPrefix + " " + cloningIssue.getSummary(),
                  cloningIssue.getDescription(), cloningIssue.getType(), cloningIssue.getAssignee(),
                  cloningIssue.getIssues().get(0), release, labels);

               downstreamIssueManager.linkIssue(clonedIssue.getKey(), key, "Cloners");

               for (String upstreamIssueKey : clonedIssue.getIssues()) {
                  Issue upstreamIssue = upstreamIssueManager.getIssue(upstreamIssueKey);
                  upstreamIssue.getIssues().add(clonedIssue.getKey());
               }

               commitTask.setResult(clonedIssue.getKey());
            } else if (type == CommitTaskType.CLONE_UPSTREAM_ISSUE) {
               Issue upstreamIssue = upstreamIssueManager.getIssue(key);
               List<String> labels = new ArrayList<>();
               labels.add(qualifier);
               if (commit.getTests().size() > 0) {
                  labels.add(UPSTREAM_TEST_COVERAGE_LABEL);
               }

               User assignee = assigneeResolver.getUserResolver().getUserFromUsername(commit.getAssignee());

               Issue downstreamIssue = downstreamIssueManager.createIssue(
                  upstreamIssue.getSummary(),
                  upstreamIssue.getDescription(), upstreamIssue.getType(), assignee.getDownstreamUsername(),
                  "https://issues.apache.org/jira/browse/" + upstreamIssue.getKey(), release, labels);

               upstreamIssue.getIssues().add(downstreamIssue.getKey());
               commitTask.setResult(downstreamIssue.getKey());
            } else {
               throw new IllegalStateException("Commit task type not supported: " + type);
            }

            commitTask.setState(CommitTaskState.EXECUTED);
         }
      }

      commit.getTasks().add(commitTask);

      return CommitTaskState.EXECUTED.equals(commitTask.getState());
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

   private List<String> getCommitTests(GitCommit upstreamCommit) throws Exception {
      List<String> tests = new ArrayList<>();
      for (String ChangedFile : gitRepository.getChangedFiles(upstreamCommit)) {
         if (ChangedFile.contains(TEST_PATH) && ChangedFile.endsWith("Test.java")) {
            tests.add(ChangedFile.substring(ChangedFile.indexOf(TEST_PATH) + TEST_PATH.length(),
                                            ChangedFile.length() - 5).replace('/', '.'));
         }
      }

      return tests;
   }

   private String selectRelease(Set<String> releases, String release) {
      String selectedRelease = null;
      for (String selectingRelease : releases) {
         if (release.equals(selectingRelease)) {
            return selectingRelease;
         } else if (selectedRelease == null || FUTURE_GA_RELEASE.equals(selectedRelease) ||
            (!FUTURE_GA_RELEASE.equals(selectingRelease) && ReleaseVersion.compare(selectingRelease, selectedRelease) > 0)) {
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
         Issue downstreamIssue = downstreamIssueManager.getIssue(downstreamIssueKey);
         if (downstreamIssue != null) {
            String downstreamIssueTargetRelease = downstreamIssue.getTargetRelease();
            if (downstreamIssueTargetRelease == null ||
               downstreamIssueTargetRelease.isEmpty() ||
               downstreamIssueTargetRelease.equals(FUTURE_GA_RELEASE)) {
               if (requireReleaseIssues) {
                  downstreamIssueTargetRelease = FUTURE_GA_RELEASE;
               } else {
                  downstreamIssueTargetRelease = release;
                  logger.warn("Downstream issue without target release: " + downstreamIssueKey);
               }
            }

            List<Issue> downstreamIssuesGroup = downstreamIssuesGroups.get(downstreamIssueTargetRelease);
            if (downstreamIssuesGroup == null) {
               downstreamIssuesGroup = new ArrayList<>();
               downstreamIssuesGroups.put(downstreamIssueTargetRelease, downstreamIssuesGroup);
            }
            downstreamIssuesGroup.add(downstreamIssue);
         } else {
            logger.warn("Downstream issue not found: " + downstreamIssueKey);
         }
      }

      return downstreamIssuesGroups;
   }
}
