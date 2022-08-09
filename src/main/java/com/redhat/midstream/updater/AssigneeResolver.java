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
import com.redhat.midstream.updater.issues.Issue;

import java.util.List;

public class AssigneeResolver {
   private UserResolver userResolver;
   private User defaultAssignee;

   public UserResolver getUserResolver() {
      return userResolver;
   }

   public User getDefaultAssignee() {
      return defaultAssignee;
   }

   public AssigneeResolver(UserResolver userResolver, User defaultAssignee) {
      this.userResolver = userResolver;
      this.defaultAssignee = defaultAssignee;
   }

   public User getAssignee(GitCommit upstreamCommit, Issue upstreamIssue, List<Issue> downstreamIssues) {
      User user;

      user = userResolver.getUserFromEmailAddress(upstreamCommit.getAuthorEmail());
      if (user != null) {
         return user;
      }

      user = userResolver.getUserFromEmailAddress(upstreamCommit.getCommitterEmail());
      if (user != null) {
         return user;
      }

      if (upstreamIssue != null) {
         user = userResolver.getUserFromUpstreamUsername(upstreamIssue.getAssignee());
         if (user != null) {
            return user;
         }

         user = userResolver.getUserFromUpstreamUsername(upstreamIssue.getReporter());
         if (user != null) {
            return user;
         }

         user = userResolver.getUserFromUpstreamUsername(upstreamIssue.getCreator());
         if (user != null) {
            return user;
         }
      }

      if (downstreamIssues != null) {
         for (Issue downstreamIssue : downstreamIssues) {
            user = userResolver.getUserFromDownstreamUsername(downstreamIssue.getAssignee());
            if (user != null) {
               return user;
            }

            user = userResolver.getUserFromDownstreamUsername(downstreamIssue.getReporter());
            if (user != null) {
               return user;
            }

            user = userResolver.getUserFromDownstreamUsername(downstreamIssue.getCreator());
            if (user != null) {
               return user;
            }
         }
      }

      return defaultAssignee;
   }
}
