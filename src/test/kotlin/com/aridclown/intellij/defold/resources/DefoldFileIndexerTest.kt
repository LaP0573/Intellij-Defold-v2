package com.aridclown.intellij.defold.resources

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefoldFileIndexerTest {
    @Test
    fun `parses go file top-level components`() {
        val content =
            """
            components {
              id: "controller"
              component: "/scripts/controller.script"
            }
            embedded_components {
              id: "sprite"
              type: "sprite"
              data: ""
            }
            """.trimIndent()

        val parsed = DefoldFileIndexer.parseFile("/foo.go", DefoldFileKind.Go, content)

        assertThat(parsed.components)
            .extracting("id", "componentRef", "typeRef")
            .containsExactlyInAnyOrder(
                tupleOf("controller", "/scripts/controller.script", null),
                tupleOf("sprite", null, "sprite")
            )
    }

    @Test
    fun `parses collection with prototype instance and embedded instance containing components`() {
        val content =
            """
            name: "main"
            instances {
              id: "hero"
              prototype: "/units/hero.go"
              position { x: 1.0 y: 2.0 }
            }
            embedded_instances {
              id: "enemy"
              data: "embedded_components {\n  id: \"sprite\"\n  type: \"sprite\"\n}\ncomponents {\n  id: \"ai\"\n  component: \"/scripts/ai.script\"\n}\n"
              position { x: 0.0 }
            }
            """.trimIndent()

        val parsed = DefoldFileIndexer.parseFile("/levels/main.collection", DefoldFileKind.Collection, content)

        assertThat(parsed.instances).hasSize(2)
        val hero = parsed.instances.first { it.id == "hero" }
        val enemy = parsed.instances.first { it.id == "enemy" }

        assertThat(hero.prototypeRef).isEqualTo("/units/hero.go")
        assertThat(hero.embeddedComponents).isEmpty()

        assertThat(enemy.embeddedComponents)
            .extracting("id", "componentRef", "typeRef")
            .containsExactlyInAnyOrder(
                tupleOf("sprite", null, "sprite"),
                tupleOf("ai", "/scripts/ai.script", null)
            )
    }

    @Test
    fun `index resolves prototype components and embedded components into instance-scoped URLs`() {
        val heroGo =
            DefoldFileIndexer.parseFile(
                "/units/hero.go",
                DefoldFileKind.Go,
                """
                components {
                  id: "controller"
                  component: "/scripts/controller.script"
                }
                """.trimIndent()
            )
        val main =
            DefoldFileIndexer.parseFile(
                "/main.collection",
                DefoldFileKind.Collection,
                """
                instances {
                  id: "hero"
                  prototype: "/units/hero.go"
                }
                embedded_instances {
                  id: "enemy"
                  data: "components {\n  id: \"sprite\"\n  component: \"/scripts/sprite.script\"\n}\n"
                }
                """.trimIndent()
            )

        val index = DefoldIndex(listOf(heroGo, main))

        val urls = index.urlsForHost("/main.collection").map { it.url }
        assertThat(urls).contains("/hero", "/hero#controller", "/enemy", "/enemy#sprite")
    }

    @Test
    fun `index recurses into sub-collections with parent-prefixed URLs`() {
        val sub =
            DefoldFileIndexer.parseFile(
                "/sub.collection",
                DefoldFileKind.Collection,
                """
                instances {
                  id: "enemy"
                  prototype: "/foo.go"
                }
                """.trimIndent()
            )
        val foo =
            DefoldFileIndexer.parseFile(
                "/foo.go",
                DefoldFileKind.Go,
                """
                components {
                  id: "sprite"
                  type: "sprite"
                }
                """.trimIndent()
            )
        val root =
            DefoldFileIndexer.parseFile(
                "/root.collection",
                DefoldFileKind.Collection,
                """
                instances {
                  id: "world"
                  collection: "/sub.collection"
                }
                """.trimIndent()
            )

        val index = DefoldIndex(listOf(sub, foo, root))

        assertThat(index.urlsForHost("/root.collection").map { it.url })
            .contains("/world", "/world/enemy", "/world/enemy#sprite")
    }

    @Test
    fun `hostsForResource finds go files that include a script as component`() {
        val gameGo =
            DefoldFileIndexer.parseFile(
                "/game.go",
                DefoldFileKind.Go,
                """
                components {
                  id: "main"
                  component: "/scripts/main.script"
                }
                """.trimIndent()
            )
        val unused =
            DefoldFileIndexer.parseFile(
                "/other.go",
                DefoldFileKind.Go,
                """
                components {
                  id: "other"
                  component: "/scripts/other.script"
                }
                """.trimIndent()
            )

        val index = DefoldIndex(listOf(gameGo, unused))
        assertThat(index.hostsForResource("/scripts/main.script")).containsExactly("/game.go")
        assertThat(index.hostsForResource("/scripts/other.script")).containsExactly("/other.go")
        assertThat(index.hostsForResource("/scripts/missing.script")).isEmpty()
    }

    @Test
    fun `index tolerates cyclic collection references without infinite recursion`() {
        val a =
            DefoldFileIndexer.parseFile(
                "/a.collection",
                DefoldFileKind.Collection,
                """
                instances {
                  id: "x"
                  collection: "/b.collection"
                }
                """.trimIndent()
            )
        val b =
            DefoldFileIndexer.parseFile(
                "/b.collection",
                DefoldFileKind.Collection,
                """
                instances {
                  id: "y"
                  collection: "/a.collection"
                }
                """.trimIndent()
            )
        val index = DefoldIndex(listOf(a, b))

        val urls = index.urlsForHost("/a.collection").map { it.url }
        assertThat(urls).contains("/x", "/x/y")
    }

    private fun tupleOf(vararg values: Any?) = org.assertj.core.groups.Tuple.tuple(*values)
}
