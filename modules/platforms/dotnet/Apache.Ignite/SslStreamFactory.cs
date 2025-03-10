/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Apache.Ignite;

using System.IO;
using System.Net.Security;
using System.Threading.Tasks;
using Internal.Common;

/// <summary>
/// Standard SSL stream factory. Can be used with or without client-side certificates.
/// </summary>
public sealed class SslStreamFactory : ISslStreamFactory
{
    /// <summary>
    /// Gets or sets client authentication options.
    /// </summary>
    public SslClientAuthenticationOptions? SslClientAuthenticationOptions { get; set; }

    /// <inheritdoc />
    public async Task<SslStream?> CreateAsync(Stream stream, string targetHost)
    {
        IgniteArgumentCheck.NotNull(stream);

        var sslStream = new SslStream(stream, false, null, null);

        var options = SslClientAuthenticationOptions ?? new SslClientAuthenticationOptions();
        options.TargetHost ??= targetHost;

        await sslStream.AuthenticateAsClientAsync(options).ConfigureAwait(false);

        return sslStream;
    }

    /// <inheritdoc />
    public override string ToString() =>
        new IgniteToStringBuilder(GetType())
            .Append(SslClientAuthenticationOptions)
            .Build();
}
