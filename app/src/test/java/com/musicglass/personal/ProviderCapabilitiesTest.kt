package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Q10: what may be offered follows from the session and the page in hand, and an unavailable action says why. */
class ProviderCapabilitiesTest {
    private val account=AccountScope.Provider("fixture-owner")
    private fun ProviderCapabilities.accountActions()=listOf(like,saveCollection,editPlaylists,feedback)

    @Test fun aVerifiedSessionCanDoEverything() {
        val caps=providerCapabilities(SessionState.Valid,account,resolverReady=true)
        assertTrue((caps.accountActions()+listOf(caps.browse,caps.stream,caps.library)).all {it==Capability.Available})
        assertNull(caps.like.reason)
    }
    @Test fun aGuestCanBrowseAndPlayAndIsToldToSignInForTheRest() {
        listOf(SessionState.SignedOut to AccountScope.Guest,SessionState.SignedOut to AccountScope.DeviceLocal,SessionState.Valid to AccountScope.Guest).forEach {(state,scope)->
            val caps=providerCapabilities(state,scope,true)
            assertEquals(Capability.Available,caps.browse);assertEquals(Capability.Available,caps.stream)
            assertEquals(listOf(ProviderCapabilities.SIGN_IN_LIKE,ProviderCapabilities.SIGN_IN_SAVE,ProviderCapabilities.SIGN_IN_EDIT,ProviderCapabilities.SIGN_IN_FEEDBACK),caps.accountActions().map {it.reason})
            assertEquals(ProviderCapabilities.SIGN_IN_LIBRARY,caps.library.reason)
            assertTrue(caps.accountActions().none {it.available})
        }
    }
    @Test fun anExpiredSessionSaysSoEverywhereItMatters() {
        val caps=providerCapabilities(SessionState.Expired,account,true)
        assertTrue((caps.accountActions()+caps.library).all {it==Capability.Unavailable(SESSION_EXPIRED_MESSAGE)})
        assertEquals(Capability.Available,caps.browse)
    }
    @Test fun whileTheSessionIsBeingCheckedAccountActionsAreLoadingNotRefused() {
        val caps=providerCapabilities(SessionState.Checking,account,true)
        assertTrue(caps.accountActions().all {it==Capability.Loading})
        assertFalse(caps.like.available);assertNull("Loading has no reason to show",caps.like.reason)
        assertEquals("What is already readable stays readable",Capability.Available,caps.library)
    }
    @Test fun anAccountThatCouldNotBeVerifiedCanReadAndCannotWrite() {
        listOf(SessionState.Unverified,SessionState.Valid,SessionState.Checking).forEach {state->
            val caps=providerCapabilities(state,AccountScope.Unverified,true)
            assertTrue(state.name,caps.accountActions().all {it==Capability.Unavailable(VERIFY_TO_SAVE_NOTICE)})
            assertEquals(Capability.Available,caps.library)
        }
    }
    @Test fun aMissingResolverStopsPlaybackAndNothingElse() {
        val caps=providerCapabilities(SessionState.Valid,account,resolverReady=false)
        assertEquals(Capability.Unavailable(ProviderCapabilities.RESOLVER_UNAVAILABLE),caps.stream)
        assertEquals(Capability.Available,caps.browse);assertEquals(Capability.Available,caps.library);assertTrue(caps.accountActions().all {it.available})
    }
    @Test fun everyStateAndScopeGivesAnAnswerAndEveryRefusalHasAReason() {
        SessionState.entries.forEach {state->listOf(AccountScope.Guest,AccountScope.DeviceLocal,AccountScope.Unverified,account).forEach {scope->listOf(true,false).forEach {ready->
            val caps=providerCapabilities(state,scope,ready)
            (caps.accountActions()+listOf(caps.browse,caps.stream,caps.library)).forEach {
                if(it is Capability.Unavailable) assertTrue("$state $scope",it.why.isNotBlank())
                assertEquals(it==Capability.Available,it.available)
            }
        }}}
    }

    // ---- one collection

    private val valid=providerCapabilities(SessionState.Valid,account,true)
    private val ownedPage=CatalogPage(emptyList(),editable=true)
    @Test fun aWholeOwnedPlaylistOffersEverything() {
        val caps=collectionCapabilities(ownedPage,complete=true,playlist=true,provider=valid)
        assertEquals(CollectionCapabilities(Capability.Available,Capability.Available,Capability.Available,Capability.Available,Capability.Available,Capability.Available),caps)
    }
    @Test fun whileACollectionIsStillLoadingItsWholeCollectionActionsAreLoading() {
        val caps=collectionCapabilities(ownedPage,complete=false,playlist=true,provider=valid)
        assertEquals(listOf(Capability.Loading,Capability.Loading,Capability.Loading,Capability.Loading),listOf(caps.playAll,caps.downloadAll,caps.sort,caps.checkMembership))
        assertEquals("Removing one entry does not need the rest",Capability.Available,caps.removeEntries)
    }
    @Test fun aTruncatedCollectionRefusesWholeCollectionActionsAndSaysWhy() {
        CollectionTruncation.entries.forEach {cut->
            val caps=collectionCapabilities(ownedPage.copy(truncation=cut),complete=true,playlist=true,provider=valid)
            listOf(caps.playAll,caps.downloadAll,caps.sort,caps.checkMembership).forEach {assertEquals(Capability.Unavailable(cut.label),it)}
            assertEquals("It can still be saved to the library: that is by reference",Capability.Available,caps.save)
        }
        assertTrue(CollectionTruncation.entries.all {it.label.isNotBlank() && it.label.all {c->c.code<128}})
    }
    @Test fun aPlaylistThatIsNotTheUsersOrAnAlbumCannotBeEdited() {
        assertEquals(Capability.Unavailable(CollectionCapabilities.READ_ONLY),collectionCapabilities(ownedPage.copy(editable=false),true,true,valid).removeEntries)
        assertEquals(Capability.Unavailable(CollectionCapabilities.NOT_A_PLAYLIST),collectionCapabilities(ownedPage,true,false,valid).removeEntries)
        val guest=providerCapabilities(SessionState.SignedOut,AccountScope.Guest,true)
        val caps=collectionCapabilities(ownedPage,true,true,guest)
        assertEquals(ProviderCapabilities.SIGN_IN_EDIT,caps.removeEntries.reason);assertEquals(ProviderCapabilities.SIGN_IN_SAVE,caps.save.reason)
        assertEquals("A guest can still play it",Capability.Available,caps.playAll)
        assertEquals(Capability.Loading,collectionCapabilities(ownedPage,true,true,providerCapabilities(SessionState.Checking,account,true)).removeEntries)
    }
}
