/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2023 The PWM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package password.pwm.svc.httpclient;

import lombok.Builder;
import lombok.Value;
import password.pwm.http.HttpContentType;
import password.pwm.http.HttpEntityDataType;
import password.pwm.http.bean.ImmutableByteArray;

import java.io.Serializable;
import java.util.Map;

@Value
@Builder
public class PwmHttpClientResponse implements Serializable, PwmHttpClientMessage
{
    private final int requestID;
    private final int statusCode;
    private final String statusPhrase;
    private final Map<String, String> headers;

    @Builder.Default
    private final HttpEntityDataType dataType = HttpEntityDataType.String;

    private final HttpContentType contentType;

    private final String body;
    private final ImmutableByteArray binaryBody;

    public String toDebugString( final ApachePwmHttpClient pwmHttpClient )
    {
        final String topLine = "HTTP response status " + statusCode + " " + statusPhrase;
        return pwmHttpClient.entityToDebugString( topLine, this );
    }

    public long size()
    {
        long size = 0;
        size += statusPhrase == null ? 0 : statusPhrase.length();
        size += body == null ? 0 : body.length();
        size += binaryBody == null ? 0 : binaryBody.size();
        if ( headers != null )
        {
            size += headers.entrySet().stream()
                    .map( e -> e.getValue().length() + e.getKey().length() )
                    .reduce( 0, Integer::sum );
        }
        return size;
    }
}
