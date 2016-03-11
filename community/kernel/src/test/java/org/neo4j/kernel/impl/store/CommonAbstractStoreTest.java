/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.store;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.mockito.InOrder;

import java.io.File;
import java.io.IOException;

import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.RecordingPageCacheTracer;
import org.neo4j.io.pagecache.RecordingPageCacheTracer.Pin;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.store.format.RecordFormat;
import org.neo4j.kernel.impl.store.format.lowlimit.LowLimitV3_0;
import org.neo4j.kernel.impl.store.id.DefaultIdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.IdGenerator;
import org.neo4j.kernel.impl.store.id.IdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.IdType;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.PageCacheRule;
import org.neo4j.test.TargetDirectory;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.neo4j.io.pagecache.RecordingPageCacheTracer.Event;
import static org.neo4j.kernel.impl.store.record.Record.NO_NEXT_PROPERTY;
import static org.neo4j.kernel.impl.store.record.Record.NO_NEXT_RELATIONSHIP;
import static org.neo4j.test.TargetDirectory.testDirForTest;

public class CommonAbstractStoreTest
{
    private static final int PAGE_SIZE = 32;
    private static final int RECORD_SIZE = 10;
    private static final int HIGH_ID = 42;

    private final IdGenerator idGenerator = mock( IdGenerator.class );
    private final IdGeneratorFactory idGeneratorFactory = mock( IdGeneratorFactory.class );
    private final PageCursor pageCursor = mock( PageCursor.class );
    private final PagedFile pageFile = mock( PagedFile.class );
    private final PageCache pageCache = mock( PageCache.class );
    private final Config config = Config.empty();
    private final File storeFile = new File( "store" );
    private final IdType idType = IdType.RELATIONSHIP; // whatever

    private static final FileSystemAbstraction fs = new DefaultFileSystemAbstraction();
    private static final TargetDirectory.TestDirectory dir = testDirForTest( CommonAbstractStoreTest.class, fs );
    private static final PageCacheRule pageCacheRule = new PageCacheRule();

    @ClassRule
    public static final RuleChain ruleChain = RuleChain.outerRule( dir ).around( pageCacheRule );

    @Before
    public void setUpMocks() throws IOException
    {
        when( idGeneratorFactory.open( any( File.class ), anyInt(), eq( idType ), anyInt(), anyInt() ) )
                .thenReturn( idGenerator );

        when( pageFile.pageSize() ).thenReturn( PAGE_SIZE );
        when( pageFile.io( anyLong(), anyInt() ) ).thenReturn( pageCursor );
        when( pageCache.map( eq( storeFile ), anyInt() ) ).thenReturn( pageFile );
    }

    @Test
    public void shouldCloseStoreFileFirstAndIdGeneratorAfter() throws Throwable
    {
        // given
        TheStore store = newStore();
        InOrder inOrder = inOrder( pageFile, idGenerator );

        // when
        store.close();

        // then
        inOrder.verify( pageFile, times( 1 ) ).close();
        inOrder.verify( idGenerator, times( 1 ) ).close();
    }

    @Test
    public void recordCursorCallsNextOnThePageCursor() throws IOException
    {
        TheStore store = newStore();
        long recordId = 4;
        long pageIdForRecord = store.pageIdForRecord( recordId );

        when( pageCursor.getCurrentPageId() ).thenReturn( pageIdForRecord );
        when( pageCursor.next( anyInt() ) ).thenReturn( true );

        RecordCursor<TheRecord> cursor = store.newRecordCursor( new TheRecord( -1 ) );
        cursor.acquire( recordId, RecordLoad.FORCE );

        cursor.next( recordId );

        InOrder order = inOrder( pageCursor );
        order.verify( pageCursor ).next( pageIdForRecord );
        order.verify( pageCursor ).shouldRetry();
    }

    @Test
    public void recordCursorPinsEachPageItReads() throws Exception
    {
        File storeFile = dir.file( "a" );
        RecordingPageCacheTracer tracer = new RecordingPageCacheTracer( Pin.class );
        PageCache pageCache = pageCacheRule.getPageCache( fs, tracer, Config.empty() );

        try ( NodeStore store = new NodeStore( storeFile, Config.empty(), new DefaultIdGeneratorFactory( fs ),
                pageCache, NullLogProvider.getInstance(), null, LowLimitV3_0.RECORD_FORMATS ) )
        {
            store.initialise( true );
            assertNull( tracer.tryObserve( Event.class ) );

            long nodeId1 = insertNodeRecordAndObservePinEvent( tracer, store );
            long nodeId2 = insertNodeRecordAndObservePinEvent( tracer, store );

            try ( RecordCursor<NodeRecord> cursor = store.newRecordCursor( store.newRecord() ) )
            {
                cursor.acquire( 0, RecordLoad.NORMAL );
                assertTrue( cursor.next( nodeId1 ) );
                assertTrue( cursor.next( nodeId2 ) );
                assertNotNull( tracer.tryObserve( Pin.class ) );
                assertNull( tracer.tryObserve( Event.class ) );
            }
        }
    }

    @SuppressWarnings( "unchecked" )
    private TheStore newStore()
    {
        RecordFormat<TheRecord> recordFormat = mock( RecordFormat.class );
        LogProvider log = NullLogProvider.getInstance();
        TheStore store = new TheStore( storeFile, config, idType, idGeneratorFactory, pageCache, log, recordFormat );
        store.initialise( false );
        return store;
    }

    private long insertNodeRecordAndObservePinEvent( RecordingPageCacheTracer tracer, NodeStore store )
    {
        long nodeId = store.nextId();
        NodeRecord record = store.newRecord();
        record.setId( nodeId );
        record.initialize( true, NO_NEXT_PROPERTY.intValue(), false, NO_NEXT_RELATIONSHIP.intValue(), 42 );
        store.prepareForCommit( record );
        store.updateRecord( record );
        assertNotNull( tracer.tryObserve( Pin.class ) );
        assertNull( tracer.tryObserve( Event.class ) );
        return nodeId;
    }

    private static class TheStore extends CommonAbstractStore<TheRecord,NoStoreHeader>
    {
        TheStore( File fileName, Config configuration, IdType idType, IdGeneratorFactory idGeneratorFactory,
                PageCache pageCache, LogProvider logProvider, RecordFormat<TheRecord> recordFormat )
        {
            super( fileName, configuration, idType, idGeneratorFactory, pageCache, logProvider, "TheType",
                    recordFormat, NoStoreHeaderFormat.NO_STORE_HEADER_FORMAT, "v1" );
        }

        @Override
        protected void initialiseNewStoreFile( PagedFile file ) throws IOException
        {
        }

        @Override
        protected int determineRecordSize()
        {
            return RECORD_SIZE;
        }

        @Override
        public long scanForHighId()
        {
            return HIGH_ID;
        }

        @Override
        public <FAILURE extends Exception> void accept( Processor<FAILURE> processor, TheRecord record ) throws FAILURE
        {
        }
    }

    private static class TheRecord extends AbstractBaseRecord
    {
        TheRecord( long id )
        {
            super( id );
        }
    }
}