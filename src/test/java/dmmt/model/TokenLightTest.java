package dmmt.model;

import dmmt.service.JsonMappers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TokenLightTest {
    @Test
    void attachedLightFollowsTheTokenAndJoinsEffectiveLights() throws Exception {
        var project = DmProject.builder().build();
        var free = DmProject.LightSource.builder().id("free").x(1).y(2).build();
        project.getLighting().getLights().add(free);
        var plain = DmProject.TokenInstance.builder().id("plain").build();
        var lit = DmProject.TokenInstance.builder().id("lit").x(10).y(20)
                .light(DmProject.LightSource.builder().id(DmProject.tokenLightId("lit")).build()).build();
        project.getTokens().add(plain);
        assertSame(project.getLighting().getLights(), project.effectiveLights());

        project.getTokens().add(lit);
        assertEquals(2, project.effectiveLights().size());
        assertEquals(1, project.getLighting().getLights().size());

        lit.setX(30);
        lit.setY(40);
        var attached = project.effectiveLights().get(1);
        assertEquals("token-light-lit", attached.getId());
        assertEquals(30, attached.getX());
        assertEquals(40, attached.getY());

        var mapper = JsonMappers.create();
        var copy = mapper.readValue(mapper.writeValueAsString(lit), DmProject.TokenInstance.class);
        assertEquals(lit, copy);
        assertNull(mapper.readValue("{}", DmProject.TokenInstance.class).getLight());
    }
}
