import axios from 'axios';
import { XMLParser } from 'fast-xml-parser';

export async function downloadAndParseXml(url) {
  const response = await axios.get(url, {
    timeout: 180000,
    responseType: 'text',
    maxContentLength: Infinity,
    maxBodyLength: Infinity
  });

  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: '@_',
    parseAttributeValue: true,
    trimValues: true,
    cdataPropName: '__cdata'
  });

  return parser.parse(response.data);
}
