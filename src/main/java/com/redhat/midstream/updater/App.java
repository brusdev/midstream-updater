package com.redhat.midstream.updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hello world!
 */
public class App {
   private final static Logger logger = LoggerFactory.getLogger(App.class);

   private final static Pattern cherryPickedCommitPattern = Pattern.compile("cherry picked from commit ([0-9a-f]{40})");
   private final static Pattern prepareReleaseCommitPattern = Pattern.compile("Prepare release ([0-9]+\\.[0-9]+\\.[0-9]+.[0-9A-Za-z]+)");

   private static final String CONFIRMED_COMMITS_OPTION = "confirmed-commits";
   private static final String CONFIRMED_UPSTREAM_ISSUES_OPTION = "confirmed-upstream-issues";
   private static final String CONFIRMED_DOWNSTREAM_ISSUES_OPTION = "confirmed-downstream-issues";
   private static final String UPSTREAM_BRANCH_OPTION = "upstream-branch";
   private static final String UPSTREAM_ISSUES_AUTH_STRING_OPTION = "upstream-issues-auth-string";
   private static final String MIDSTREAM_BRANCH_OPTION = "midstream-branch";
   private static final String DOWNSTREAM_ISSUES_AUTH_STRING_OPTION = "downstream-issues-auth-string";
   private static final String DOWNSTREAM_ISSUES_CUSTOMER_PRIORITY = "downstream-issues-customer-priority";
   private static final String DOWNSTREAM_ISSUES_SECURITY_IMPACT = "downstream-issues-security-impact";
   private static final String RELEASE_OPTION = "release";
   private static final String QUALIFIER_OPTION = "qualifier";
   private static final String ASSIGNEE_OPTION = "assignee";
   private static final String CHECK_INCOMPLETE_COMMITS_OPTION = "check-incomplete-commits";

   public static void main(String[] args) throws Exception {
      // Parse arguments
      CommandLine line = null;
      Options options = new Options();
      options.addRequiredOption("a", ASSIGNEE_OPTION, true, "the default assignee, i.e. dbruscin");
      options.addRequiredOption("r", RELEASE_OPTION, true, "the release, i.e. AMQ 7.10.0.GA");
      options.addRequiredOption("q", QUALIFIER_OPTION, true, "the qualifier, i.e. CR1");
      options.addRequiredOption("u", UPSTREAM_BRANCH_OPTION, true, "the upstream branch to cherry-pick from, i.e. main");
      options.addRequiredOption("m", MIDSTREAM_BRANCH_OPTION, true, "the midstream branch to cherry-pick to, i.e. 2.16.0.jbossorg-x");
      options.addOption(null, CONFIRMED_COMMITS_OPTION, true, "the confirmed commits");

      Option confirmedDownstreamIssuesOption = new Option(null, CONFIRMED_DOWNSTREAM_ISSUES_OPTION, true, "the confirmed downstream issues, commits related to other downstream issues with a different target release will be skipped");
      confirmedDownstreamIssuesOption.setOptionalArg(true);
      options.addOption(confirmedDownstreamIssuesOption);

      Option confirmedUpstreamIssuesOption = new Option(null, CONFIRMED_UPSTREAM_ISSUES_OPTION, true, "the confirmed upstream issues, commits related to other upstream issues without a downstream issue will be skipped");
      confirmedUpstreamIssuesOption.setOptionalArg(true);
      options.addOption(confirmedUpstreamIssuesOption);

      options.addOption(null, UPSTREAM_ISSUES_AUTH_STRING_OPTION, true, "the auth string to access upstream issues, i.e. \"Bearer ...\"");
      options.addOption(null, DOWNSTREAM_ISSUES_AUTH_STRING_OPTION, true, "the auth string to access downstream issues, i.e. \"Bearer ...\"");
      options.addOption(null, DOWNSTREAM_ISSUES_CUSTOMER_PRIORITY, true, "the customer priority to filter downstream issues, i.e. HIGH");
      options.addOption(null, DOWNSTREAM_ISSUES_SECURITY_IMPACT, true, "the security impact to filter downstream issues, i.e. IMPORTANT");

      Option checkIncompleteCommitsOption = new Option(null, CHECK_INCOMPLETE_COMMITS_OPTION, true, "check tasks of cherry-picked commits");
      checkIncompleteCommitsOption.setOptionalArg(true);
      options.addOption(checkIncompleteCommitsOption);
      CommandLineParser parser = new DefaultParser();

      try {
         line = parser.parse(options, args);
      } catch (ParseException e) {
         logger.error("Error on parsing arguments", e);
      }

      String assignee = line.getOptionValue(ASSIGNEE_OPTION);

      String release = line.getOptionValue(RELEASE_OPTION);
      String qualifier = line.getOptionValue(QUALIFIER_OPTION);
      ReleaseVersion candidateReleaseVersion = new ReleaseVersion(release + "." + qualifier);
      Boolean requireReleaseIssues = candidateReleaseVersion.getPatch() > 0;

      String upstreamBranch = line.getOptionValue(UPSTREAM_BRANCH_OPTION);

      String midstreamBranch = line.getOptionValue(MIDSTREAM_BRANCH_OPTION);

      String downstreamIssuesAuthString = line.getOptionValue(DOWNSTREAM_ISSUES_AUTH_STRING_OPTION);

      CustomerPriority downstreamIssuesCustomerPriority = CustomerPriority.LOW;
      if (line.hasOption(DOWNSTREAM_ISSUES_CUSTOMER_PRIORITY)) {
         downstreamIssuesCustomerPriority = CustomerPriority.fromName(
            line.getOptionValue(DOWNSTREAM_ISSUES_CUSTOMER_PRIORITY));
      }

      SecurityImpact downstreamIssuesSecurityImpact = SecurityImpact.LOW;
      if (line.hasOption(DOWNSTREAM_ISSUES_SECURITY_IMPACT)) {
         downstreamIssuesSecurityImpact = SecurityImpact.fromName(
            line.getOptionValue(DOWNSTREAM_ISSUES_SECURITY_IMPACT));
      }

      String upstreamIssuesAuthString = line.getOptionValue(UPSTREAM_ISSUES_AUTH_STRING_OPTION);

      String confirmedCommitsFilename = null;
      if (line.hasOption(CONFIRMED_COMMITS_OPTION)) {
         confirmedCommitsFilename = line.getOptionValue(CONFIRMED_COMMITS_OPTION);
      }

      String confirmedUpstreamIssueKeys = null;
      if (line.hasOption(CONFIRMED_UPSTREAM_ISSUES_OPTION)) {
         confirmedUpstreamIssueKeys = line.getOptionValue(CONFIRMED_UPSTREAM_ISSUES_OPTION, "");
      }

      String confirmedDownstreamIssueKeys = null;
      if (line.hasOption(CONFIRMED_DOWNSTREAM_ISSUES_OPTION)) {
         confirmedDownstreamIssueKeys = line.getOptionValue(CONFIRMED_DOWNSTREAM_ISSUES_OPTION, "");
      }

      boolean checkIncompleteCommits = true;
      if (line.hasOption(CHECK_INCOMPLETE_COMMITS_OPTION)) {
         checkIncompleteCommits = Boolean.parseBoolean(line.getOptionValue(CHECK_INCOMPLETE_COMMITS_OPTION, "true"));
      }

      // Initialize target directory
      File targetDir = new File("target");
      if (!targetDir.exists()) {
         targetDir.mkdir();
      }


      // Initialize git
      Git git;
      File repoDir = new File(targetDir, "activemq-artemis-repo");

      if (repoDir.exists()) {
         git = Git.open(repoDir);
         git.fetch().setRemote("origin").call();
         git.fetch().setRemote("upstream").call();
      } else {
         git = Git.cloneRepository()
            .setURI("https://github.com/rh-messaging/activemq-artemis.git")
            .setDirectory(repoDir)
            .call();
         git.remoteAdd().setName("upstream").setUri(new URIish("https://github.com/apache/activemq-artemis.git")).call();
         git.fetch().setRemote("upstream").call();
      }

      if (git.getRepository().resolve(midstreamBranch) != null) {
         git.checkout().setName("upstream/" + upstreamBranch).setForced(true).call();
         git.branchDelete().setBranchNames(midstreamBranch).setForce(true).call();
      }
      git.branchCreate().setName(midstreamBranch).setForce(true).setStartPoint("origin/" + midstreamBranch).call();
      git.checkout().setName(midstreamBranch).setForced(true).call();


      // Initialize gson
      Gson gson = new GsonBuilder().setPrettyPrinting().create();


      //Load users
      User[] usersArray;
      File usersFile = new File(targetDir, "users.json");
      if (usersFile.exists()) {
         usersArray = gson.fromJson(FileUtils.readFileToString(usersFile, Charset.defaultCharset()), User[].class);
      } else {
         usersArray = new User[0];
      }

      //Initialize UserResolver
      UserResolver userResolver = new UserResolver(usersArray);


      //Initialize AssigneeResolver
      AssigneeResolver assigneeResolver = new AssigneeResolver(userResolver, userResolver.getUserFromUsername(assignee));


      // Load upstream issues
      Issue[] upstreamIssuesArray;
      File upstreamIssuesFile = new File(targetDir, "upstream-issues.json");
      IssueClient upstreamIssueClient = new IssueClient("https://issues.apache.org/jira/rest/api/2", upstreamIssuesAuthString);
      if (upstreamIssuesFile.exists()) {
         upstreamIssuesArray = gson.fromJson(FileUtils.readFileToString(upstreamIssuesFile, Charset.defaultCharset()), Issue[].class);
      } else {
         upstreamIssuesArray = upstreamIssueClient.loadProjectIssues("ARTEMIS", false);
      }

      Map<String, Issue> upstreamIssues = new HashMap();
      for (Issue issue : upstreamIssuesArray) {
         upstreamIssues.put(issue.getKey(), issue);
      }


      // Load downstream issues
      Issue[] downstreamIssuesArray;
      File downstreamIssuesFile = new File(targetDir, "downstream-issues.json");
      IssueClient downstreamIssueClient = new IssueClient("https://issues.redhat.com/rest/api/2", downstreamIssuesAuthString);
      if (downstreamIssuesFile.exists()) {
         downstreamIssuesArray = gson.fromJson(FileUtils.readFileToString(downstreamIssuesFile, Charset.defaultCharset()), Issue[].class);
      } else {
         downstreamIssuesArray = downstreamIssueClient.loadProjectIssues("ENTMQBR", true);

         for (Issue issue : downstreamIssuesArray) {
            for (String upstreamIssueKey : issue.getIssues()) {
               Issue upstreamIssue = upstreamIssues.get(upstreamIssueKey);

               if (upstreamIssue != null) {
                  logger.debug("upstream issue " + upstreamIssueKey + " linked to downstream issue " + issue.getKey());
                  if (!upstreamIssue.getIssues().contains(issue.getKey())) {
                     upstreamIssue.getIssues().add(issue.getKey());
                  }
               } else {
                  logger.warn("upstream issue " + upstreamIssueKey + " not found for downstream issue " + issue.getKey());
               }
            }
         }
      }

      Map<String, Issue> downstreamIssues = new HashMap();
      for (Issue issue : downstreamIssuesArray) {
         downstreamIssues.put(issue.getKey(), issue);
      }


      // Store upstream issues
      if (!upstreamIssuesFile.exists()) {
         FileUtils.writeStringToFile(upstreamIssuesFile, gson.toJson(upstreamIssuesArray), Charset.defaultCharset());
      }


      // Store downstream issues
      if (!downstreamIssuesFile.exists()) {
         FileUtils.writeStringToFile(downstreamIssuesFile, gson.toJson(downstreamIssuesArray), Charset.defaultCharset());
      }


      // Load upstream commits
      Deque<RevCommit> upstreamCommits = new ArrayDeque<>();
      for (RevCommit commit : git.log()
         .not(git.getRepository().resolve("origin/" + midstreamBranch))
         .add(git.getRepository().resolve("upstream/" + upstreamBranch))
         .call()) {
         if (!commit.getShortMessage().startsWith("Merge pull request")) {
            upstreamCommits.push(commit);
         }
      }


      // Load cherry-picked commits
      HashMap<String, Map.Entry<ReleaseVersion, RevCommit>> cherryPickedCommits = new HashMap<>();
      ReleaseVersion cherryPickedReleaseVersion = candidateReleaseVersion;
      for (RevCommit commit : git.log()
         .not(git.getRepository().resolve("upstream/" + upstreamBranch))
         .add(git.getRepository().resolve("origin/" + midstreamBranch))
         .call()) {

         Matcher prepareReleaseCommitMatcher = prepareReleaseCommitPattern.matcher(commit.getShortMessage());
         if (prepareReleaseCommitMatcher.find()) {
            logger.info("prepare release commit found: " + commit.getName() + " - " + commit.getShortMessage());
            cherryPickedReleaseVersion = new ReleaseVersion(prepareReleaseCommitMatcher.group(1));
         }

         Matcher cherryPickedCommitMatcher = cherryPickedCommitPattern.matcher(commit.getFullMessage());
         if (cherryPickedCommitMatcher.find()) {
            String cherryPickedCommitName = cherryPickedCommitMatcher.group(1);

            RevCommit cherryPickedCommit = upstreamCommits.stream().filter(
               revCommit -> revCommit.getName().equals(cherryPickedCommitName)).findAny().orElse(null);
            if (cherryPickedCommit == null) {
               logger.error("cherry-picked commit not found: " + cherryPickedCommitName + " - " + commit.getShortMessage());

               cherryPickedCommit = upstreamCommits.stream().filter(
                  revCommit -> revCommit.getShortMessage().equals(commit.getShortMessage())).findAny().orElse(null);

               if (cherryPickedCommit != null) {
                  logger.warn("similar cherry-picked commit found: " + cherryPickedCommit.getName() + " - " + cherryPickedCommit.getShortMessage());
               }
            }
            
            if (cherryPickedCommit != null) {
               cherryPickedCommits.put(cherryPickedCommit.getName(), new AbstractMap.SimpleEntry<>(cherryPickedReleaseVersion, commit));
            }
         }
      }


      // Load confirmed commits
      Map<String, Commit> confirmedCommits = new HashMap<>();
      if (confirmedCommitsFilename != null) {
         File confirmedCommitsFile = new File(confirmedCommitsFilename);
         Commit[] confirmedCommitsArray = gson.fromJson(FileUtils.readFileToString(
            confirmedCommitsFile, Charset.defaultCharset()), Commit[].class);
         for (Commit confirmedCommit : confirmedCommitsArray) {
            confirmedCommits.put(confirmedCommit.getUpstreamCommit(), confirmedCommit);
         }
      }


      // Load confirmed upstream issues
      Map<String, Issue> confirmedUpstreamIssues = null;
      if (confirmedUpstreamIssueKeys != null) {
         confirmedUpstreamIssues = new HashMap<>();
         for (String confirmedUpstreamIssueKey : confirmedUpstreamIssueKeys.split(",")) {
            Issue confirmedUpstreamIssue = upstreamIssues.get(confirmedUpstreamIssueKey);
            if (confirmedUpstreamIssue == null) {
               logger.warn("Upstream issue not found: " + confirmedUpstreamIssueKey);
            }
            confirmedUpstreamIssues.put(confirmedUpstreamIssueKey, confirmedUpstreamIssue);
         }
      }


      // Load confirmed downstream issues
      Map<String, Issue> confirmedDownstreamIssues = null;
      if (confirmedDownstreamIssueKeys != null) {
         confirmedDownstreamIssues = new HashMap<>();
         for (String confirmedDownstreamIssueKey : confirmedDownstreamIssueKeys.split(",")) {
            Issue confirmedDownstreamIssue = upstreamIssues.get(confirmedDownstreamIssueKey);
            if (confirmedDownstreamIssue == null) {
               logger.warn("Downstream issue not found: " + confirmedDownstreamIssueKey);
            }
            confirmedDownstreamIssues.put(confirmedDownstreamIssueKey, confirmedDownstreamIssue);
         }
      }


      // Init commit parser
      CommitProcessor commitProcessor = new CommitProcessor(git, candidateReleaseVersion, requireReleaseIssues,
         upstreamIssueClient, downstreamIssueClient, assigneeResolver, upstreamIssues, downstreamIssues,
         cherryPickedCommits, confirmedCommits, confirmedUpstreamIssues, confirmedDownstreamIssues,
         downstreamIssuesCustomerPriority, downstreamIssuesSecurityImpact, checkIncompleteCommits);


      // Process upstream commits
      List<Commit> commits = new ArrayList<>();
      try {
         for (RevCommit upstreamCommit : upstreamCommits) {
            logger.info("Upstream commit: " + upstreamCommit.getName() + " - " + upstreamCommit.getShortMessage());

            commits.add(commitProcessor.process(upstreamCommit));
         }
      } finally {
         // Store commits
         File commitsFile = new File(targetDir, "commits.json");
         if (commitsFile.exists()) {
            commitsFile.delete();
         }
         FileUtils.writeStringToFile(commitsFile, gson.toJson(commits.stream()
            .filter(commit -> (commit.getState() != CommitState.SKIPPED && commit.getState() != CommitState.DONE) ||
               (commit.getState() == CommitState.DONE && commit.getTasks().stream().anyMatch(CommitTask::isExecuted)))
            .collect(Collectors.toList())), Charset.defaultCharset());
         //FileUtils.writeStringToFile(commitsFile, gson.toJson(commits), Charset.defaultCharset());

         // Store upstream issues
         FileUtils.writeStringToFile(upstreamIssuesFile, gson.toJson(upstreamIssues.values()), Charset.defaultCharset());


         // Store downstream issues
         FileUtils.writeStringToFile(downstreamIssuesFile, gson.toJson(downstreamIssues.values()), Charset.defaultCharset());
      }

      File payloadFile = new File(targetDir, "payload.csv");
      try (CSVPrinter printer = new CSVPrinter(new FileWriter(payloadFile), CSVFormat.DEFAULT
         .withHeader(new String[]{ "state", "release", "commit", "author", "summary", "upstreamIssue", "downstreamIssues", "upstreamTestCoverage"}))) {

         for (Commit commit : commits.stream()
            .filter(commit -> (commit.getState() == CommitState.DONE && commit.getDownstreamCommit() != null && commit.getReleaseVersion().compareWithoutQualifierTo(candidateReleaseVersion) == 0))
            .collect(Collectors.toList())) {
            printer.printRecord(commit.getState(), commit.getReleaseVersion(), commit.getUpstreamCommit(), commit.getAuthor(), commit.getSummary(),
                                commit.getUpstreamIssue(), String.join(",", commit.getDownstreamIssues()), commit.hasUpstreamTestCoverage());
         }

         for (Commit commit : commits.stream()
            .filter(commit -> ((commit.getState() != CommitState.SKIPPED && commit.getState() != CommitState.DONE) || (commit.getState() == CommitState.DONE && commit.getTasks().size() > 0)))
            .collect(Collectors.toList())) {
            printer.printRecord(commit.getState(), commit.getReleaseVersion(), commit.getUpstreamCommit(), commit.getAuthor(), commit.getSummary(),
                                commit.getUpstreamIssue(), String.join(",", commit.getDownstreamIssues()), commit.hasUpstreamTestCoverage());
         }
      }
   }
}