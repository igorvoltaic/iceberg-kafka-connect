/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.tabular.iceberg.connect.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tabular.iceberg.connect.events.CommitCompletePayload;
import io.tabular.iceberg.connect.events.CommitReadyPayload;
import io.tabular.iceberg.connect.events.CommitRequestPayload;
import io.tabular.iceberg.connect.events.CommitResponsePayload;
import io.tabular.iceberg.connect.events.CommitTablePayload;
import io.tabular.iceberg.connect.events.Event;
import io.tabular.iceberg.connect.events.EventTestUtil;
import io.tabular.iceberg.connect.events.EventType;
import io.tabular.iceberg.connect.events.TableName;
import io.tabular.iceberg.connect.events.TopicPartitionOffset;
import java.util.List;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.types.Types.StructType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class CoordinatorTest extends ChannelTestBase {

  @Test
  public void testCommitAppend() {
    long ts = System.currentTimeMillis();
    UUID commitId =
        coordinatorTest(ImmutableList.of(EventTestUtil.createDataFile()), ImmutableList.of(), ts);

    assertThat(producer.history()).hasSize(3);
    assertCommitTable(1, commitId, ts);
    assertCommitComplete(2, commitId, ts);

    verify(appendOp).appendFile(notNull());
    verify(appendOp).commit();

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(appendOp, times(3)).set(captor.capture(), notNull());
    assertThat(captor.getAllValues().get(0)).startsWith("kafka.connect.offsets.");
    assertThat(captor.getAllValues().get(1)).isEqualTo("kafka.connect.commit-id");
    assertThat(captor.getAllValues().get(2)).isEqualTo("kafka.connect.vtts");

    verify(deltaOp, times(0)).commit();
  }

  @Test
  public void testCommitDelta() {
    long ts = System.currentTimeMillis();
    UUID commitId =
        coordinatorTest(
            ImmutableList.of(EventTestUtil.createDataFile()),
            ImmutableList.of(EventTestUtil.createDeleteFile()),
            ts);

    assertThat(producer.history()).hasSize(3);
    assertCommitTable(1, commitId, ts);
    assertCommitComplete(2, commitId, ts);

    verify(appendOp, times(0)).commit();

    verify(deltaOp).addRows(notNull());
    verify(deltaOp).addDeletes(notNull());
    verify(deltaOp).commit();

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(deltaOp, times(3)).set(captor.capture(), notNull());
    assertThat(captor.getAllValues().get(0)).startsWith("kafka.connect.offsets.");
    assertThat(captor.getAllValues().get(1)).isEqualTo("kafka.connect.commit-id");
    assertThat(captor.getAllValues().get(2)).isEqualTo("kafka.connect.vtts");
  }

  @Test
  public void testCommitNoFiles() {
    long ts = System.currentTimeMillis();
    UUID commitId = coordinatorTest(ImmutableList.of(), ImmutableList.of(), ts);

    assertThat(producer.history()).hasSize(2);
    assertCommitComplete(1, commitId, ts);

    verify(appendOp, times(0)).commit();
    verify(deltaOp, times(0)).commit();
  }

  @Test
  public void testCommitError() {
    doThrow(RuntimeException.class).when(appendOp).commit();

    coordinatorTest(ImmutableList.of(EventTestUtil.createDataFile()), ImmutableList.of(), 0L);

    // no commit messages sent
    assertThat(producer.history()).hasSize(1);
  }

  private void assertCommitTable(int idx, UUID commitId, long ts) {
    byte[] bytes = producer.history().get(idx).value();
    Event commitTable = Event.decode(bytes);
    assertThat(commitTable.type()).isEqualTo(EventType.COMMIT_TABLE);
    CommitTablePayload commitTablePayload = (CommitTablePayload) commitTable.payload();
    assertThat(commitTablePayload.commitId()).isEqualTo(commitId);
    assertThat(commitTablePayload.tableName().toIdentifier().toString()).isEqualTo("db.tbl");
    assertThat(commitTablePayload.vtts()).isEqualTo(ts);
  }

  private void assertCommitComplete(int idx, UUID commitId, long ts) {
    byte[] bytes = producer.history().get(idx).value();
    Event commitComplete = Event.decode(bytes);
    assertThat(commitComplete.type()).isEqualTo(EventType.COMMIT_COMPLETE);
    CommitCompletePayload commitCompletePayload = (CommitCompletePayload) commitComplete.payload();
    assertThat(commitCompletePayload.commitId()).isEqualTo(commitId);
    assertThat(commitCompletePayload.vtts()).isEqualTo(ts);
  }

  private UUID coordinatorTest(List<DataFile> dataFiles, List<DeleteFile> deleteFiles, long ts) {
    when(config.commitIntervalMs()).thenReturn(0);
    when(config.commitTimeoutMs()).thenReturn(Integer.MAX_VALUE);

    Coordinator coordinator = new Coordinator(catalog, config, ImmutableList.of(), clientFactory);
    coordinator.start();

    // init consumer after subscribe()
    initConsumer();

    coordinator.process();

    assertThat(producer.transactionCommitted()).isTrue();
    assertThat(producer.history()).hasSize(1);
    verify(appendOp, times(0)).commit();
    verify(deltaOp, times(0)).commit();

    byte[] bytes = producer.history().get(0).value();
    Event commitRequest = Event.decode(bytes);
    assertThat(commitRequest.type()).isEqualTo(EventType.COMMIT_REQUEST);

    UUID commitId = ((CommitRequestPayload) commitRequest.payload()).commitId();

    Event commitResponse =
        new Event(
            config.controlGroupId(),
            EventType.COMMIT_RESPONSE,
            new CommitResponsePayload(
                StructType.of(),
                commitId,
                new TableName(ImmutableList.of("db"), "tbl"),
                dataFiles,
                deleteFiles));
    bytes = Event.encode(commitResponse);
    consumer.addRecord(new ConsumerRecord<>(CTL_TOPIC_NAME, 0, 1, "key", bytes));

    Event commitReady =
        new Event(
            config.controlGroupId(),
            EventType.COMMIT_READY,
            new CommitReadyPayload(
                commitId, ImmutableList.of(new TopicPartitionOffset("topic", 1, 1L, ts))));
    bytes = Event.encode(commitReady);
    consumer.addRecord(new ConsumerRecord<>(CTL_TOPIC_NAME, 0, 2, "key", bytes));

    when(config.commitIntervalMs()).thenReturn(0);

    coordinator.process();

    return commitId;
  }
}
