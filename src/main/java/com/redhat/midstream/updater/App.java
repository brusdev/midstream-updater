package com.redhat.midstream.updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.redhat.midstream.updater.git.GitCommit;
import com.redhat.midstream.updater.git.GitRepository;
import com.redhat.midstream.updater.git.JGitRepository;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
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
   private static final String SCRATCH_OPTION = "scratch";


   public static void main(String[] args) throws Exception {
      // Parse arguments
      Options options = new Options();
      options.addOption(createOption("a", ASSIGNEE_OPTION, true, true, false, "the default assignee, i.e. dbruscin"));
      options.addOption(createOption("r", RELEASE_OPTION, true, true, false, "the release, i.e. AMQ 7.10.0.GA"));
      options.addOption(createOption("q", QUALIFIER_OPTION, true, true, false, "the qualifier, i.e. CR1"));
      options.addOption(createOption("u", UPSTREAM_BRANCH_OPTION, true, true, false, "the upstream branch to cherry-pick from, i.e. main"));
      options.addOption(createOption("m", MIDSTREAM_BRANCH_OPTION, true, true, false, "the midstream branch to cherry-pick to, i.e. 2.16.0.jbossorg-x"));

      options.addOption(createOption(null, CONFIRMED_COMMITS_OPTION, false, true, false, "the confirmed commits"));
      options.addOption(createOption(null, CONFIRMED_DOWNSTREAM_ISSUES_OPTION, false, true, true, "the confirmed downstream issues, commits related to other downstream issues with a different target release will be skipped"));
      options.addOption(createOption(null, CONFIRMED_UPSTREAM_ISSUES_OPTION, false, true, true, "the confirmed upstream issues, commits related to other upstream issues without a downstream issue will be skipped"));

      options.addOption(createOption(null, UPSTREAM_ISSUES_AUTH_STRING_OPTION, false, true, false, "the auth string to access upstream issues, i.e. \"Bearer ...\""));
      options.addOption(createOption(null, DOWNSTREAM_ISSUES_AUTH_STRING_OPTION, false, true, false, "the auth string to access downstream issues, i.e. \"Bearer ...\""));
      options.addOption(createOption(null, DOWNSTREAM_ISSUES_CUSTOMER_PRIORITY, false, true, false, "the customer priority to filter downstream issues, i.e. HIGH"));
      options.addOption(createOption(null, DOWNSTREAM_ISSUES_SECURITY_IMPACT, false, true, false, "the security impact to filter downstream issues, i.e. IMPORTANT"));

      options.addOption(createOption(null, CHECK_INCOMPLETE_COMMITS_OPTION, false, true, true, "check tasks of cherry-picked commits"));
      options.addOption(createOption(null, SCRATCH_OPTION, false, false, false, "scratch"));

      CommandLine line = null;
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

      boolean scratch = line.hasOption(SCRATCH_OPTION);

      // Initialize target directory
      File targetDir = new File("target");
      if (!targetDir.exists()) {
         targetDir.mkdir();
      }


      // Initialize git
      GitRepository gitRepository = new JGitRepository();
      File repoDir = new File(targetDir, "activemq-artemis-repo");

      if (repoDir.exists()) {
         gitRepository.open(repoDir);
         gitRepository.fetch("origin");
         gitRepository.fetch("upstream");
      } else {
         gitRepository.clone("https://github.com/rh-messaging/activemq-artemis.git", repoDir);
         gitRepository.remoteAdd("upstream", "https://github.com/apache/activemq-artemis.git");
         gitRepository.fetch("upstream");
      }

      if (!gitRepository.branchExists(midstreamBranch)) {
         gitRepository.checkout("upstream/" + upstreamBranch);
         gitRepository.branchDelete(midstreamBranch);
      }
      gitRepository.branchCreate(midstreamBranch, "origin/" + midstreamBranch);
      gitRepository.checkout(midstreamBranch);


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
      Deque<GitCommit> upstreamCommits = new ArrayDeque<>();
      for (GitCommit commit : gitRepository.log("upstream/" + upstreamBranch, "origin/" + midstreamBranch)) {
         if (!commit.getShortMessage().startsWith("Merge pull request")) {
            upstreamCommits.push(commit);
         }
      }


      // Load cherry-picked commits
      HashMap<String, Map.Entry<ReleaseVersion, GitCommit>> cherryPickedCommits = new HashMap<>();
      ReleaseVersion cherryPickedReleaseVersion = candidateReleaseVersion;
      for (GitCommit commit : gitRepository.log("origin/" + midstreamBranch, "upstream/" + upstreamBranch)) {
         Matcher prepareReleaseCommitMatcher = prepareReleaseCommitPattern.matcher(commit.getShortMessage());
         if (prepareReleaseCommitMatcher.find()) {
            logger.info("prepare release commit found: " + commit.getName() + " - " + commit.getShortMessage());
            cherryPickedReleaseVersion = new ReleaseVersion(prepareReleaseCommitMatcher.group(1));
         } else if (commit.getShortMessage().startsWith("7.8.")) {
            logger.info("legacy release commit found: " + commit.getName() + " - " + commit.getShortMessage());
            cherryPickedReleaseVersion = new ReleaseVersion(commit.getShortMessage());
         }

         Matcher cherryPickedCommitMatcher = cherryPickedCommitPattern.matcher(commit.getFullMessage());
         if (cherryPickedCommitMatcher.find()) {
            String cherryPickedCommitName = cherryPickedCommitMatcher.group(1);

            GitCommit cherryPickedCommit = upstreamCommits.stream().filter(
               upstreamCommit -> upstreamCommit.getName().equals(cherryPickedCommitName)).findAny().orElse(null);
            if (cherryPickedCommit == null) {
               logger.error("cherry-picked commit not found: " + cherryPickedCommitName + " - " + commit.getShortMessage());

               cherryPickedCommit = upstreamCommits.stream().filter(
                  upstreamCommit -> upstreamCommit.getShortMessage().equals(commit.getShortMessage())).findAny().orElse(null);

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
      CommitProcessor commitProcessor = new CommitProcessor(gitRepository, candidateReleaseVersion, requireReleaseIssues,
         upstreamIssueClient, downstreamIssueClient, assigneeResolver, upstreamIssues, downstreamIssues,
         cherryPickedCommits, confirmedCommits, confirmedUpstreamIssues, confirmedDownstreamIssues,
         downstreamIssuesCustomerPriority, downstreamIssuesSecurityImpact, checkIncompleteCommits, scratch);


      // Process upstream commits
      List<Commit> commits = new ArrayList<>();
      try {
         for (GitCommit upstreamCommit : upstreamCommits) {
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
               (commit.getState() == CommitState.DONE && commit.getTasks().stream()
                  .anyMatch(commitTask -> CommitTaskState.EXECUTED.equals(commitTask.getState()))))
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
            .filter(commit -> (commit.getState() == CommitState.DONE && commit.getDownstreamCommit() != null &&  candidateReleaseVersion.compareWithoutQualifierTo(new ReleaseVersion(commit.getReleaseVersion())) == 0))
            .collect(Collectors.toList())) {
            printer.printRecord(commit.getState(), commit.getReleaseVersion(), commit.getUpstreamCommit(), commit.getAuthor(), commit.getSummary(),
                                commit.getUpstreamIssue(), String.join(",", commit.getDownstreamIssues()), commit.getTests().size() > 0);
         }

         for (Commit commit : commits.stream()
            .filter(commit -> ((commit.getState() != CommitState.SKIPPED && commit.getState() != CommitState.DONE) || (commit.getState() == CommitState.DONE && commit.getTasks().size() > 0)))
            .collect(Collectors.toList())) {
            printer.printRecord(commit.getState(), commit.getReleaseVersion(), commit.getUpstreamCommit(), commit.getAuthor(), commit.getSummary(),
                                commit.getUpstreamIssue(), String.join(",", commit.getDownstreamIssues()), commit.getTests().size() > 0);
         }
      }
   }

   private static Option createOption(String opt, String longOpt, boolean required, boolean hasArg, boolean hasOptionalArg, String description) {
      Option option = new Option(opt, longOpt, hasArg, description);
      option.setRequired(required);
      option.setOptionalArg(hasOptionalArg);

      return option;
   }
}