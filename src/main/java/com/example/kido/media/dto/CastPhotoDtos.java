package com.example.kido.media.dto;

import com.example.kido.media.cast.CastPhoto;

/** Response shapes for cast/crew photos. */
public final class CastPhotoDtos {

    private CastPhotoDtos() {}

    public record CastMemberDto(String name, String photoUrl) {

        /** A name with no cached/fetchable photo yet — renders as initials, same as today. */
        public static CastMemberDto unresolved(String name) {
            return new CastMemberDto(name, null);
        }

        public static CastMemberDto from(CastPhoto photo) {
            String url = photo.getState() == CastPhoto.State.READY
                    ? "/api/media/cast/" + photo.getId() + "/photo"
                    : null;
            return new CastMemberDto(photo.getDisplayName(), url);
        }
    }
}
