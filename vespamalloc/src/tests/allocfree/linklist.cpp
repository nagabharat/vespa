// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "producerconsumer.h"
#include <vespa/vespalib/testkit/testapp.h>
#include <vespamalloc/malloc/allocchunk.h>
#include <vespamalloc/util/callstack.h>
#include <vespa/log/log.h>
LOG_SETUP("linklist_test");

using vespalib::Consumer;
using vespalib::Producer;
using vespalib::ProducerConsumer;

TEST_SETUP(Test);

//-----------------------------------------------------------------------------

template <size_t MinSizeClassC, size_t MaxSizeClassMultiAllocC>
class MemBlockT : public vespamalloc::CommonT<MinSizeClassC>
{
public:
    typedef vespamalloc::StackEntry<vespamalloc::StackReturnEntry> Stack;
    enum {
        MaxSizeClassMultiAlloc = MaxSizeClassMultiAllocC,
        SizeClassSpan = (MaxSizeClassMultiAllocC-MinSizeClassC)
    };
    MemBlockT() : _ptr(NULL) { }
    MemBlockT(void * p) : _ptr(p) { }
    MemBlockT(void * p, size_t /*sz*/) : _ptr(p) { }
    void *ptr()               { return _ptr; }
    const void *ptr()   const { return _ptr; }
    bool validAlloc()   const { return _ptr != NULL; }
    bool validFree()    const { return _ptr != NULL; }
    void setExact(size_t )    { }
    void alloc(bool )         { }
    void threadId(int )       { }
    void free()               { }
    size_t size()       const { return 0; }
    bool allocated()    const { return false; }
    int threadId()      const { return 0; }
    void info(FILE *, unsigned level=0) const  { level = 0; }
    Stack * callStack()                   { return NULL; }
    size_t callStackLen()           const { return 0; }

    static size_t adjustSize(size_t sz)   { return sz; }
    static size_t unAdjustSize(size_t sz) { return sz; }
    static void dumpInfo(size_t level);
private:
    void * _ptr;
};

typedef MemBlockT<5, 20> DummyMemBlock;

typedef vespamalloc::AFList<DummyMemBlock> List;

const size_t NumBlocks((64*(32+2)+16)*2);

List globalList[NumBlocks];

class LinkIn : public Consumer {
public:
    LinkIn(List::AtomicHeadPtr & list, uint32_t maxQueue, bool inverse);
private:
    List::AtomicHeadPtr & _head;
    virtual void consume(void * p) override {
        List * l((List *) p);
        if ( ! ((l >= &globalList[0]) && (l < &globalList[NumBlocks]))) { abort(); }
        List::linkIn(_head, l, l);
    }
};

LinkIn::LinkIn(List::AtomicHeadPtr & list, uint32_t maxQueue, bool inverse) :
    Consumer (maxQueue, inverse),
    _head(list)
{
}

//-----------------------------------------------------------------------------

class LinkOut : public Producer {
public:
    LinkOut(List::AtomicHeadPtr & list, uint32_t cnt, LinkIn &target)
        : Producer(cnt, target), _head(list) {}
private:
    List::AtomicHeadPtr & _head;
    virtual void * produce()       override {
        void *p = List::linkOut(_head);
        List *l((List *)p);
        if ( ! ((l >= &globalList[0]) && (l < &globalList[NumBlocks]))) { abort(); }
        return p;
    }
};

//-----------------------------------------------------------------------------

class LinkInOutAndIn : public ProducerConsumer {
public:
    LinkInOutAndIn(List::AtomicHeadPtr & list, uint32_t cnt, bool inverse)
        : ProducerConsumer(cnt, inverse), _head(list) { }
private:
    List::AtomicHeadPtr & _head;
    virtual void * produce()       override {
        void *p = List::linkOut(_head);
        List *l((List *)p);
        if ( !((l >= &globalList[0]) && (l < &globalList[NumBlocks]))) { abort(); }
        return p;
    }
    virtual void consume(void * p) override {
        List * l((List *) p);
        if ( !((l >= &globalList[0]) && (l < &globalList[NumBlocks]))) { abort(); }
        List::linkIn(_head, l, l);
    }
};

//-----------------------------------------------------------------------------

int Test::Main() {
    int duration = 10;
    if (_argc > 1) {
        duration = atoi(_argv[1]);
    }
    TEST_INIT("allocfree_test");

    ASSERT_EQUAL(1024ul, sizeof(List));

    FastOS_ThreadPool      pool(128000);
    List::AtomicHeadPtr    sharedList(List::HeadPtr(nullptr, 1));
    fprintf(stderr, "Start populating list\n");
    for (size_t i=0; i < NumBlocks; i++) {
        List * l(&globalList[i]);
        List::linkIn(sharedList, l, l);
    }
    fprintf(stderr, "Finished populating list with %ld elements\n", NumBlocks);
    fprintf(stderr, "Start verifying result 1.\n");
    for (size_t i=0; i < NumBlocks; i++) {
        List *l =  List::linkOut(sharedList);
        ASSERT_TRUE((l >= &globalList[0]) && (l < &globalList[NumBlocks]));
    }
    List *n =  List::linkOut(sharedList);
    ASSERT_TRUE(n == NULL);

    List::HeadPtr tmp(sharedList.load());
    tmp._tag = 1;
    sharedList.store(tmp);
    fprintf(stderr, "Start populating list\n");
    for (size_t i=0; i < NumBlocks; i++) {
        List * l(&globalList[i]);
        List::linkIn(sharedList, l, l);
    }
    fprintf(stderr, "Finished populating list with %ld elements\n", NumBlocks);
    LinkIn                c1(sharedList, 64, false);
    LinkIn                c2(sharedList, 64, true);
    LinkOut             p1(sharedList, 32, c1);
    LinkOut             p2(sharedList, 32, c2);
    LinkInOutAndIn  pc1(sharedList, 16, false);
    LinkInOutAndIn  pc2(sharedList, 16, true);

    ASSERT_TRUE(pool.NewThread(&c1, NULL) != NULL);
    ASSERT_TRUE(pool.NewThread(&c2, NULL) != NULL);
    ASSERT_TRUE(pool.NewThread(&p1, NULL) != NULL);
    ASSERT_TRUE(pool.NewThread(&p2, NULL) != NULL);
    ASSERT_TRUE(pool.NewThread(&pc1, NULL) != NULL);
    ASSERT_TRUE(pool.NewThread(&pc2, NULL) != NULL);

    for (; duration > 0; --duration) {
        LOG(info, "%d seconds left...", duration);
        FastOS_Thread::Sleep(1000);
    }
    pool.Close();
    fprintf(stderr, "Did (%lu + %lu) = %lu linkIn operations\n",
            c1.operations(), c2.operations(), c1.operations() + c2.operations());
    fprintf(stderr, "Did (%lu + %lu) = %lu linkOut operations\n",
            p1.operations(), p2.operations(), p1.operations() + p2.operations());
    fprintf(stderr, "Did (%lu + %lu) = %lu linkInOut operations\n",
            pc1.operationsConsumed(), pc2.operationsConsumed(), pc1.operationsConsumed() + pc2.operationsConsumed());
    fprintf(stderr, "Did %lu Total operations\n",
            c1.operations() + c2.operations() + p1.operations() + p2.operations() + pc1.operationsConsumed() + pc2.operationsConsumed());
    fprintf(stderr, "Start verifying result 2.\n");
    for (size_t i=0; i < NumBlocks; i++) {
        List *l =  List::linkOut(sharedList);
        ASSERT_TRUE((l >= &globalList[0]) && (l < &globalList[NumBlocks]));
    }
    n =  List::linkOut(sharedList);
    ASSERT_TRUE(n == NULL);
    TEST_DONE();
}
