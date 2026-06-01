using System.Buffers;
using EffinitiveFramework.Core;
using EffinitiveFramework.Core.Http;

namespace effinitive.Tests;

public class UploadEndpoint : NoRequestEndpointBase<string>
{
    protected override string Method => "POST";
    protected override string Route => "/upload";
    protected override string ContentType => "text/plain";

    public override async ValueTask<string> HandleAsync(CancellationToken ct)
    {
        long size = 0;

        if (HttpContext?.BodyDeferred == true && HttpContext.BodyStream != null)
        {
            var buffer = ArrayPool<byte>.Shared.Rent(65536);
            try
            {
                int read;
                while ((read = await HttpContext.BodyStream.ReadAsync(buffer.AsMemory(0, 65536), ct)) > 0)
                    size += read;
            }
            finally
            {
                ArrayPool<byte>.Shared.Return(buffer);
            }
        }
        else
        {
            size = HttpContext?.Body.Length ?? 0;
        }

        return size.ToString();
    }
}
