// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroupsTest {
    private val header = """"format":"studiopocket-groups","formatVersion":1,"updatedAt":"x","generator":"y""""
    private fun file(vararg groups: String) = """{$header,"groups":[${groups.joinToString(",")}]}"""
    private fun group(id: String, vararg playlists: String, extra: String = "") =
        """{"id":"$id","title":"${id.uppercase()}"$extra,"playlists":[${playlists.joinToString(",") { "\"$it\"" }}]}"""

    private val library = LibraryLoader(Fixtures.root).load().playlists

    @Test
    fun parsesTheSharedFixture() {
        val defs = Groups.parse(File(Fixtures.root, "groups.json").readText())
        assertEquals(listOf("g-bedtime", "g-music", "g-empty"), defs.map { it.id })
        assertEquals(GroupDef("g-bedtime", "Bedtime", "groups/g-bedtime.png", listOf("bedtime", "gone-away")), defs[0])
        assertNull(defs[1].icon)
        assertEquals("covers", defs[1].display)
        assertEquals(listOf("songs"), defs[1].covers)
        assertEquals("icon", defs[0].display)
        assertTrue(defs[2].playlists.isEmpty())
    }

    @Test
    fun toleratesEveryInvalidFixture() {
        fun parse(name: String) = Groups.parse(File(Fixtures.invalid, name).readText())
        assertEquals(listOf("g-a"), parse("groups-reserved-id.json").map { it.id })
        assertEquals(listOf(listOf("bedtime", "songs"), listOf("songs")), parse("groups-playlist-in-two-groups.json").map { it.playlists })
        assertEquals(LibraryFormat.MAX_GROUP_TITLE, parse("groups-title-too-long.json")[0].title.length)
        assertNull(parse("groups-icon-outside-groups.json")[0].icon)
        assertEquals(LibraryFormat.MAX_GROUPS, parse("groups-too-many.json").size)
        assertTrue(parse("groups-wrong-version.json").isEmpty())
        assertTrue(parse("groups-cover-not-in-group.json")[0].covers.isEmpty())
        assertEquals(Groups.STACK, parse("groups-too-many-covers.json")[0].covers.size)
        assertNull(parse("groups-image-outside-groups.json")[0].image)
        assertTrue(Groups.parse("{ nope").isEmpty())
        assertTrue(Groups.parse("""{$header,"groups":{}}""").isEmpty())
    }

    @Test
    fun skipsMalformedGroupsAndIdsButKeepsTheRest() {
        val defs = Groups.parse(file(
            "42",
            """{"id":"no-title","playlists":["a"]}""",
            group("Bad Id", "a"),
            group("g-a", "a", "Not An Id", "a", "b"),
            group("g-a", "c"),
            """{"id":"g-b","title":"B"}""",
        ))
        assertEquals(listOf("g-a", "g-b"), defs.map { it.id })
        assertEquals(listOf("a", "b"), defs[0].playlists)
        assertTrue(defs[1].playlists.isEmpty())
    }

    @Test
    fun buildsRecentUserGroupsThenAllFromTheFixtures() {
        val defs = Groups.parse(File(Fixtures.root, "groups.json").readText())
        val built = Groups.build(library, defs, RecentStore(Fixtures.root).read(), Fixtures.root)
        assertFalse(built.flat)
        assertEquals(listOf("recent", "g-bedtime", "g-music", "all"), built.groups.map { it.key })
        assertEquals(listOf(PlaylistGroup.Kind.RECENT, PlaylistGroup.Kind.USER, PlaylistGroup.Kind.USER, PlaylistGroup.Kind.ALL), built.groups.map { it.kind })
        // Recent: the removed id is dropped; the hidden (truncated) playlist never shows anywhere.
        assertEquals(listOf("songs", "bedtime"), built.groups[0].playlists.map { it.id })
        assertEquals(listOf("bedtime"), built.groups[1].playlists.map { it.id })
        assertEquals(listOf("songs"), built.groups[2].playlists.map { it.id })
        assertEquals(listOf("bedtime", "songs"), built.groups[3].playlists.map { it.id })
        assertNotNull(built.groups[1].iconFile)
        assertNull(built.groups[2].iconFile)
        assertEquals(listOf("icon"), faces(built.groups[1]))
        assertEquals(listOf("songs"), faces(built.groups[2])) // its chosen cover, a single card
        assertEquals("Bedtime", built.groups[1].title)
    }

    @Test
    fun firstGroupWinsAndUnknownIdsAreDropped() {
        val defs = Groups.parse(file(group("g-a", "songs", "nope"), group("g-b", "songs", "bedtime")))
        val built = Groups.build(library, defs, emptyList(), Fixtures.root)
        assertEquals(listOf("songs"), built.groups[1].playlists.map { it.id })
        assertEquals(listOf("bedtime"), built.groups[2].playlists.map { it.id })
    }

    @Test
    fun hidesGroupsWithNothingOnThePlayer() {
        val defs = Groups.parse(file(group("g-a", "gone"), group("g-b", "broken"), group("g-c"), group("g-d", "songs")))
        assertEquals(listOf("recent", "g-d", "all"), Groups.build(library, defs, emptyList(), Fixtures.root).groups.map { it.key })
    }

    @Test
    fun aMissingIconFileFallsBackToTheGlyph() {
        val defs = Groups.parse(file(group("g-a", "songs", extra = ""","icon":"groups/missing.png"""")))
        assertNull(Groups.build(library, defs, emptyList(), Fixtures.root).groups[1].iconFile)
    }

    @Test
    fun recentIsFilteredDedupedAndCappedAtFive() {
        val many = (1..8).map { "p-$it" }
        val playlists = many.map { id -> library[0].copy(manifest = library[0].manifest.copy(id = id, title = id)) }
        val defs = Groups.parse(file(group("g-a", "p-1")))
        val built = Groups.build(playlists, defs, listOf("p-8", "gone", "p-8", "p-3", "p-1", "p-2", "p-4", "p-5", "p-6"), Fixtures.root)
        assertEquals(listOf("p-8", "p-3", "p-1", "p-2", "p-4"), built.groups[0].playlists.map { it.id })
        assertEquals(many, built.groups.last().playlists.map { it.id })
    }

    @Test
    fun noUserGroupsMeansTheFlatCoverFlow() {
        for (defs in listOf(emptyList(), Groups.parse(file(group("g-a", "gone"))))) {
            val built = Groups.build(library, defs, listOf("songs"), Fixtures.root)
            assertTrue(built.flat)
            assertEquals(listOf("all"), built.groups.map { it.key })
            assertEquals(listOf("bedtime", "songs"), built.groups[0].playlists.map { it.id })
        }
        assertFalse(Groups.SHOW_GROUPS_WITHOUT_USER_GROUPS)
    }

    @Test
    fun loadsFromTheRootAndTreatsAMissingFileAsNoGroups() {
        assertEquals(3, Groups.load(Fixtures.root).size)
        assertTrue(Groups.load(File("/definitely/not/here")).isEmpty())
    }

    private fun faces(g: PlaylistGroup) = g.faces.map {
        when (it) {
            is PlaylistGroup.Face.Icon -> "icon"
            is PlaylistGroup.Face.Image -> "image"
            is PlaylistGroup.Face.Cover -> it.playlist.id
        }
    }

    @Test
    fun parsesDisplayImageAndCoversTolerantly() {
        val defs = Groups.parse(file(
            group("g-a", "songs", "bedtime", extra = ""","display":"covers","image":"groups/g-a-image.jpg","covers":["bedtime","nope","bedtime"]"""),
            group("g-b", "songs", extra = ""","display":"sparkly","image":"../escape.jpg""""),
            group("g-c", "songs"),
        ))
        assertEquals("covers", defs[0].display)
        assertEquals("groups/g-a-image.jpg", defs[0].image)
        assertEquals(listOf("bedtime"), defs[0].covers)
        assertEquals("icon", defs[1].display)
        assertNull(defs[1].image)
        assertEquals("icon", defs[2].display)
    }

    @Test
    fun buildsEachDisplayModesStack() {
        val icon = File(Fixtures.root, "groups/g-bedtime.png").path.removePrefix(Fixtures.root.path + "/")
        val defs = Groups.parse(file(
            group("g-icon", "songs"),
            group("g-image", "bedtime", extra = ""","display":"image","image":"$icon""""),
            group("g-image-missing", "x1", "x2", extra = ""","display":"image","image":"groups/none.jpg""""),
        ))
        val more = Groups.parse(file(
            group("g-covers", "songs", "bedtime", extra = ""","display":"covers","covers":["bedtime"]"""),
        ))
        val recentMode = Groups.parse(file(group("g-recent", "bedtime", "songs", extra = ""","display":"recent","image":"$icon"""")))
        val a = Groups.build(library, defs, emptyList(), Fixtures.root).groups
        assertEquals(listOf("icon"), faces(a[1]))
        assertEquals(listOf("image"), faces(a[2]))
        assertEquals(listOf("icon"), faces(a[0])) // empty Recent shows its clock alone
        assertEquals(listOf("bedtime", "songs"), faces(a.last())) // "broken" has no cover, so no card
        val b = Groups.build(library, more, listOf("songs"), Fixtures.root).groups
        assertEquals(listOf("bedtime"), faces(b[1]))
        assertEquals(listOf("songs"), faces(b[0]))
        val r = Groups.build(library, recentMode, listOf("songs"), Fixtures.root).groups
        assertEquals(listOf("image", "songs", "bedtime"), faces(r[1]))
    }
}
