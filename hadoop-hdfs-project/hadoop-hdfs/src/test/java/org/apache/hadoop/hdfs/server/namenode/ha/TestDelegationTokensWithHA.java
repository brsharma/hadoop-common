/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.ha;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HAUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenSelector;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.hdfs.web.JsonUtil;
import org.apache.hadoop.hdfs.web.resources.ExceptionHandler;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.StandbyException;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.SecurityUtilTestHelper;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mortbay.util.ajax.JSON;

import com.google.common.base.Joiner;

import org.apache.hadoop.fs.CommonConfigurationKeysPublic;

/**
 * Test case for client support of delegation tokens in an HA cluster.
 * See HDFS-2904 for more info.
 **/
public class TestDelegationTokensWithHA {
  private static Configuration conf = new Configuration();
  private static final Log LOG =
    LogFactory.getLog(TestDelegationTokensWithHA.class);
  private static MiniDFSCluster cluster;
  private static NameNode nn0;
  private static NameNode nn1;
  private static FileSystem fs;
  private static DelegationTokenSecretManager dtSecretManager;
  private static DistributedFileSystem dfs;

  @BeforeClass
  public static void setupCluster() throws Exception {
    conf.setBoolean(
        DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_ALWAYS_USE_KEY, true);
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTH_TO_LOCAL,
        "RULE:[2:$1@$0](JobTracker@.*FOO.COM)s/@.*//" + "DEFAULT");

    cluster = new MiniDFSCluster.Builder(conf)
      .nnTopology(MiniDFSNNTopology.simpleHATopology())
      .numDataNodes(0)
      .build();
    cluster.waitActive();
    
    nn0 = cluster.getNameNode(0);
    nn1 = cluster.getNameNode(1);
    fs = HATestUtil.configureFailoverFs(cluster, conf);
    dfs = (DistributedFileSystem)fs;

    cluster.transitionToActive(0);
    dtSecretManager = NameNodeAdapter.getDtSecretManager(
        nn0.getNamesystem());
  }

  @AfterClass
  public static void shutdownCluster() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
    }
  }


  @Before
  public void prepTest() {
    SecurityUtilTestHelper.setTokenServiceUseIp(true);
  }
  
  @Test
  public void testDelegationTokenDFSApi() throws Exception {
    final Token<DelegationTokenIdentifier> token =
        getDelegationToken(fs, "JobTracker");
    DelegationTokenIdentifier identifier = new DelegationTokenIdentifier();
    byte[] tokenId = token.getIdentifier();
    identifier.readFields(new DataInputStream(
             new ByteArrayInputStream(tokenId)));

    // Ensure that it's present in the NN's secret manager and can
    // be renewed directly from there.
    LOG.info("A valid token should have non-null password, " +
        "and should be renewed successfully");
    assertTrue(null != dtSecretManager.retrievePassword(identifier));
    dtSecretManager.renewToken(token, "JobTracker");
    
    // Use the client conf with the failover info present to check
    // renewal.
    Configuration clientConf = dfs.getConf();
    doRenewOrCancel(token, clientConf, TokenTestAction.RENEW);
    
    // Using a configuration that doesn't have the logical nameservice
    // configured should result in a reasonable error message.
    Configuration emptyConf = new Configuration();
    try {
      doRenewOrCancel(token, emptyConf, TokenTestAction.RENEW);
      fail("Did not throw trying to renew with an empty conf!");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains(
          "Unable to map logical nameservice URI", ioe);
    }

    
    // Ensure that the token can be renewed again after a failover.
    cluster.transitionToStandby(0);
    cluster.transitionToActive(1);
    doRenewOrCancel(token, clientConf, TokenTestAction.RENEW);
    
    doRenewOrCancel(token, clientConf, TokenTestAction.CANCEL);
  }
  
  @SuppressWarnings("deprecation")
  @Test
  public void testDelegationTokenWithDoAs() throws Exception {
    final Token<DelegationTokenIdentifier> token =
        getDelegationToken(fs, "JobTracker");
    final UserGroupInformation longUgi = UserGroupInformation
        .createRemoteUser("JobTracker/foo.com@FOO.COM");
    final UserGroupInformation shortUgi = UserGroupInformation
        .createRemoteUser("JobTracker");
    longUgi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        DistributedFileSystem dfs = (DistributedFileSystem)
            HATestUtil.configureFailoverFs(cluster, conf);
        // try renew with long name
        dfs.renewDelegationToken(token);
        return null;
      }
    });
    shortUgi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        DistributedFileSystem dfs = (DistributedFileSystem)
            HATestUtil.configureFailoverFs(cluster, conf);
        dfs.renewDelegationToken(token);
        return null;
      }
    });
    longUgi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        DistributedFileSystem dfs = (DistributedFileSystem)
            HATestUtil.configureFailoverFs(cluster, conf);
        // try cancel with long name
        dfs.cancelDelegationToken(token);
        return null;
      }
    });
  }
  
  @Test
  public void testHAUtilClonesDelegationTokens() throws Exception {
    final Token<DelegationTokenIdentifier> token =
        getDelegationToken(fs, "JobTracker");

    UserGroupInformation ugi = UserGroupInformation.createRemoteUser("test");
    
    URI haUri = new URI("hdfs://my-ha-uri/");
    token.setService(HAUtil.buildTokenServiceForLogicalUri(haUri));
    ugi.addToken(token);

    Collection<InetSocketAddress> nnAddrs = new HashSet<InetSocketAddress>();
    nnAddrs.add(nn0.getNameNodeAddress());
    nnAddrs.add(nn1.getNameNodeAddress());
    HAUtil.cloneDelegationTokenForLogicalUri(ugi, haUri, nnAddrs);
    
    Collection<Token<? extends TokenIdentifier>> tokens = ugi.getTokens();
    assertEquals(3, tokens.size());
    
    LOG.info("Tokens:\n" + Joiner.on("\n").join(tokens));
    DelegationTokenSelector dts = new DelegationTokenSelector();
    
    // check that the token selected for one of the physical IPC addresses
    // matches the one we received
    for (InetSocketAddress addr : nnAddrs) {
      Text ipcDtService = SecurityUtil.buildTokenService(addr);
      Token<DelegationTokenIdentifier> token2 =
          dts.selectToken(ipcDtService, ugi.getTokens());
      assertNotNull(token2);
      assertArrayEquals(token.getIdentifier(), token2.getIdentifier());
      assertArrayEquals(token.getPassword(), token2.getPassword());
    }
    
    // switch to host-based tokens, shouldn't match existing tokens 
    SecurityUtilTestHelper.setTokenServiceUseIp(false);
    for (InetSocketAddress addr : nnAddrs) {
      Text ipcDtService = SecurityUtil.buildTokenService(addr);
      Token<DelegationTokenIdentifier> token2 =
          dts.selectToken(ipcDtService, ugi.getTokens());
      assertNull(token2);
    }
    
    // reclone the tokens, and see if they match now
    HAUtil.cloneDelegationTokenForLogicalUri(ugi, haUri, nnAddrs);
    for (InetSocketAddress addr : nnAddrs) {
      Text ipcDtService = SecurityUtil.buildTokenService(addr);
      Token<DelegationTokenIdentifier> token2 =
          dts.selectToken(ipcDtService, ugi.getTokens());
      assertNotNull(token2);
      assertArrayEquals(token.getIdentifier(), token2.getIdentifier());
      assertArrayEquals(token.getPassword(), token2.getPassword());
    }    
  }

  /**
   * HDFS-3062: DistributedFileSystem.getCanonicalServiceName() throws an
   * exception if the URI is a logical URI. This bug fails the combination of
   * ha + mapred + security.
   */
  @Test
  public void testDFSGetCanonicalServiceName() throws Exception {
    URI hAUri = HATestUtil.getLogicalUri(cluster);
    String haService = HAUtil.buildTokenServiceForLogicalUri(hAUri).toString();
    assertEquals(haService, dfs.getCanonicalServiceName());
    final String renewer = UserGroupInformation.getCurrentUser().getShortUserName();
    final Token<DelegationTokenIdentifier> token =
        getDelegationToken(dfs, renewer);
    assertEquals(haService, token.getService().toString());
    // make sure the logical uri is handled correctly
    token.renew(dfs.getConf());
    token.cancel(dfs.getConf());
  }
  
  @Test
  public void testHdfsGetCanonicalServiceName() throws Exception {
    Configuration conf = dfs.getConf();
    URI haUri = HATestUtil.getLogicalUri(cluster);
    AbstractFileSystem afs =  AbstractFileSystem.createFileSystem(haUri, conf);    
    String haService = HAUtil.buildTokenServiceForLogicalUri(haUri).toString();
    assertEquals(haService, afs.getCanonicalServiceName());
    Token<?> token = afs.getDelegationTokens(
        UserGroupInformation.getCurrentUser().getShortUserName()).get(0);
    assertEquals(haService, token.getService().toString());
    // make sure the logical uri is handled correctly
    token.renew(conf);
    token.cancel(conf);
  }

  /**
   * Test if StandbyException can be thrown from StandbyNN, when it's requested for 
   * password. (HDFS-6475). With StandbyException, the client can failover to try
   * activeNN.
   */
  @Test
  public void testDelegationTokenStandbyNNAppearFirst() throws Exception {
    // make nn0 the standby NN, and nn1 the active NN
    cluster.transitionToStandby(0);
    cluster.transitionToActive(1);

    final DelegationTokenSecretManager stSecretManager = 
        NameNodeAdapter.getDtSecretManager(
            nn1.getNamesystem());

    // create token
    final Token<DelegationTokenIdentifier> token =
        getDelegationToken(fs, "JobTracker");
    final DelegationTokenIdentifier identifier = new DelegationTokenIdentifier();
    byte[] tokenId = token.getIdentifier();
    identifier.readFields(new DataInputStream(
             new ByteArrayInputStream(tokenId)));

    assertTrue(null != stSecretManager.retrievePassword(identifier));

    final UserGroupInformation ugi = UserGroupInformation
        .createRemoteUser("JobTracker");
    ugi.addToken(token);
    
    ugi.doAs(new PrivilegedExceptionAction<Object>() {
      @Override
      public Object run() {
        try {
          try {
            byte[] tmppw = dtSecretManager.retrievePassword(identifier);
            fail("InvalidToken with cause StandbyException is expected"
                + " since nn0 is standby");
            return tmppw;
          } catch (IOException e) {
            // Mimic the UserProvider class logic (server side) by throwing
            // SecurityException here
            throw new SecurityException(
                "Failed to obtain user group information: " + e, e);
          }
        } catch (Exception oe) {
          //
          // The exception oe caught here is
          //     java.lang.SecurityException: Failed to obtain user group
          //     information: org.apache.hadoop.security.token.
          //     SecretManager$InvalidToken: StandbyException
          //
          HttpServletResponse response = mock(HttpServletResponse.class);
          ExceptionHandler eh = new ExceptionHandler();
          eh.initResponse(response);
          
          // The Response (resp) below is what the server will send to client          
          //
          // BEFORE HDFS-6475 fix, the resp.entity is
          //     {"RemoteException":{"exception":"SecurityException",
          //      "javaClassName":"java.lang.SecurityException",
          //      "message":"Failed to obtain user group information: 
          //      org.apache.hadoop.security.token.SecretManager$InvalidToken:
          //        StandbyException"}}
          // AFTER the fix, the resp.entity is
          //     {"RemoteException":{"exception":"StandbyException",
          //      "javaClassName":"org.apache.hadoop.ipc.StandbyException",
          //      "message":"Operation category READ is not supported in
          //       state standby"}}
          //
          Response resp = eh.toResponse(oe);
          
          // Mimic the client side logic by parsing the response from server
          //
          Map<?, ?> m = (Map<?, ?>)JSON.parse(resp.getEntity().toString());
          RemoteException re = JsonUtil.toRemoteException(m);
          Exception unwrapped = ((RemoteException)re).unwrapRemoteException(
              StandbyException.class);
          assertTrue (unwrapped instanceof StandbyException);
          return null;
        }
      }
    });
  }

  @SuppressWarnings("unchecked")
  private Token<DelegationTokenIdentifier> getDelegationToken(FileSystem fs,
      String renewer) throws IOException {
    final Token<?> tokens[] = fs.addDelegationTokens(renewer, null);
    assertEquals(1, tokens.length);
    return (Token<DelegationTokenIdentifier>) tokens[0];
  }
  enum TokenTestAction {
    RENEW, CANCEL;
  }
  
  private static void doRenewOrCancel(
      final Token<DelegationTokenIdentifier> token, final Configuration conf,
      final TokenTestAction action)
      throws IOException, InterruptedException {
    UserGroupInformation.createRemoteUser("JobTracker").doAs(
        new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            switch (action) {
            case RENEW:
              token.renew(conf);
              break;
            case CANCEL:
              token.cancel(conf);
              break;
            default:
              fail("bad action:" + action);
            }
            return null;
          }
        });
  }
}
